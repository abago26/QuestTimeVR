// QuestTime VR - immersive panorama presentation.
//
// The panorama is handed to the compositor as an XR_KHR_composition_layer_cylinder
// layer rather than being drawn by us. That matters: a cylinder layer *is* a
// cylindrical projection, which is exactly what QuickTime VR 1.0 stores, so the
// image never gets reprojected or resampled, and the compositor renders it at full
// display resolution. It also means this file contains no shaders, no meshes and no
// view matrices - only enough EGL to satisfy the OpenXR graphics binding.
//
// Geometry contract: centralAngle is the horizontal sweep in radians, aspectRatio is
// the texture's width/height. The runtime derives the vertical extent as
// atan(centralAngle / (2 * aspectRatio)), which for a 2496x768 full turn is 44.03
// degrees - self-consistent with the pixels by construction.

#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <atomic>
#include <cmath>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define TAG "QuestTimeVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jobject g_activity = nullptr;          // global ref
std::atomic<bool> g_quit{false};
std::atomic<bool> g_running{false};
// Written by the render thread, read from the JNI thread when Kotlin asks why
// nothing appeared - so it needs a lock, not just an assignment.
std::mutex g_errorMutex;
std::string g_error;

// First failure wins: it is the one that explains the rest.
void setError(std::string message) {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    if (g_error.empty()) g_error = std::move(message);
}

void clearError() {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    g_error.clear();
}

std::string lastError() {
    std::lock_guard<std::mutex> lock(g_errorMutex);
    return g_error;
}

struct Pano {
    std::vector<uint8_t> rgba;   // cylindrical: one image. cubic: six faces, in order.
    int width = 0;
    int height = 0;
    float centralAngle = 6.2831853f;
    float aspect = 3.25f;
    bool cube = false;           // six square faces rather than a cylinder
    int faceSize = 0;
};

bool xrOk(XrResult r, const char *what) {
    if (XR_SUCCEEDED(r)) return true;
    char buf[128];
    snprintf(buf, sizeof(buf), "%s failed (%d)", what, static_cast<int>(r));
    LOGE("%s", buf);
    setError(buf);
    return false;
}

// Snap turn from either thumbstick. Snap rather than smooth: a panorama gives the
// inner ear nothing to agree with, and continuous rotation against a fixed image is
// what makes people queasy. A flick turns, and the stick must return to centre before
// it will turn again, so resting a thumb on it does not spin the world.
constexpr float kTurnDegrees = 45.0f;
constexpr float kTurnEngage = 0.7f;
constexpr float kTurnRelease = 0.3f;

#define XR_TRY(expr, what) do { if (!xrOk((expr), (what))) return false; } while (0)

// ---------------------------------------------------------------------------
// EGL - a context is required to create GLES swapchains; nothing is drawn into it.
// ---------------------------------------------------------------------------
class Egl {
public:
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    bool create() {
        display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display == EGL_NO_DISPLAY) { setError("eglGetDisplay failed"); return false; }
        if (!eglInitialize(display, nullptr, nullptr)) { setError("eglInitialize failed"); return false; }

        const EGLint attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
            EGL_NONE,
        };
        EGLint numConfigs = 0;
        if (!eglChooseConfig(display, attribs, &config, 1, &numConfigs) || numConfigs < 1) {
            setError("eglChooseConfig found no ES3 config");
            return false;
        }

        const EGLint ctxAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
        if (context == EGL_NO_CONTEXT) { setError("eglCreateContext failed"); return false; }

        const EGLint pbAttribs[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
        surface = eglCreatePbufferSurface(display, config, pbAttribs);
        if (surface == EGL_NO_SURFACE) { setError("eglCreatePbufferSurface failed"); return false; }

        if (!eglMakeCurrent(display, surface, surface, context)) {
            setError("eglMakeCurrent failed");
            return false;
        }
        return true;
    }

    void destroy() {
        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            eglTerminate(display);
        }
        display = EGL_NO_DISPLAY;
        surface = EGL_NO_SURFACE;
        context = EGL_NO_CONTEXT;
    }
};

// ---------------------------------------------------------------------------
// The session
// ---------------------------------------------------------------------------
class Viewer {
public:
    // Every early return here used to skip shutdown, leaking the EGL display and
    // any OpenXR handles already created - which then made the *next* attempt fail
    // for a different reason than the first. Bring-up and teardown are paired.
    bool run(const Pano &pano) {
        const bool ok = bringUp(pano);
        if (ok) loop(pano);
        shutdown();
        return ok;
    }

private:
    bool bringUp(const Pano &pano) {
        if (!initLoader()) return false;
        if (!createInstance()) return false;
        if (!getSystem()) return false;
        if (!egl_.create()) return false;
        if (!createSession()) return false;
        pano_ = &pano;
        return createSwapchain(pano);
    }

    XrInstance instance_ = XR_NULL_HANDLE;
    XrSystemId system_ = XR_NULL_SYSTEM_ID;
    XrSession session_ = XR_NULL_HANDLE;
    XrSpace space_ = XR_NULL_HANDLE;
    XrSwapchain swapchain_ = XR_NULL_HANDLE;
    XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
    bool sessionRunning_ = false;
    int32_t swWidth_ = 0, swHeight_ = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images_;
    std::vector<char> filled_;

    const Pano *pano_ = nullptr;
    XrEnvironmentBlendMode blendMode_ = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    bool handsAvailable_ = false;
    XrHandTrackerEXT leftHand_ = XR_NULL_HANDLE;
    PFN_xrCreateHandTrackerEXT createHandTracker_ = nullptr;
    PFN_xrLocateHandJointsEXT locateHandJoints_ = nullptr;
    PFN_xrDestroyHandTrackerEXT destroyHandTracker_ = nullptr;
    bool pinching_ = false;
    uint64_t pinchCount_ = 0;
    uint64_t handPolls_ = 0;
    bool aimAvailable_ = false;
    uint32_t maxSwapW_ = 0, maxSwapH_ = 0;
    bool cubeAvailable_ = false;
    XrActionSet actionSet_ = XR_NULL_HANDLE;
    XrAction turnAction_ = XR_NULL_HANDLE;
    XrPath handPaths_[2] = {XR_NULL_PATH, XR_NULL_PATH};
    bool controllersReady_ = false;
    bool turnArmed_ = true;
    /** Accumulated snap-turn, applied to every layer's pose. */
    float yaw_ = 0.0f;
    Egl egl_;

    bool initLoader() {
        PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
        if (XR_FAILED(xrGetInstanceProcAddr(
                XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                reinterpret_cast<PFN_xrVoidFunction *>(&initializeLoader))) ||
            initializeLoader == nullptr) {
            setError("xrInitializeLoaderKHR unavailable - is an OpenXR runtime installed?");
            return false;
        }
        XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        info.applicationVM = g_vm;
        info.applicationContext = g_activity;
        XR_TRY(initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&info)),
               "xrInitializeLoaderKHR");
        return true;
    }

    bool hasExtension(const std::vector<XrExtensionProperties> &props, const char *name) {
        for (const auto &p : props) if (strcmp(p.extensionName, name) == 0) return true;
        return false;
    }

    bool createInstance() {
        uint32_t count = 0;
        XR_TRY(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr),
               "xrEnumerateInstanceExtensionProperties");
        std::vector<XrExtensionProperties> props(count, {XR_TYPE_EXTENSION_PROPERTIES});
        XR_TRY(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data()),
               "xrEnumerateInstanceExtensionProperties");

        const char *needed[] = {
            XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
            XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME,
            XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME,
        };
        std::vector<const char *> enabled;
        for (const char *n : needed) {
            if (!hasExtension(props, n)) {
                const std::string missing =
                    std::string("Runtime is missing required extension ") + n;
                setError(missing);
                LOGE("%s", missing.c_str());
                return false;
            }
            enabled.push_back(n);
        }
        if (hasExtension(props, XR_KHR_COMPOSITION_LAYER_CUBE_EXTENSION_NAME)) {
            enabled.push_back(XR_KHR_COMPOSITION_LAYER_CUBE_EXTENSION_NAME);
            cubeAvailable_ = true;
        } else {
            LOGI("no %s - cubic panoramas unavailable",
                 XR_KHR_COMPOSITION_LAYER_CUBE_EXTENSION_NAME);
        }

        // Hand tracking is a nice-to-have: without it the viewer still works, you
        // just cannot raise the menu by pinching.
        if (hasExtension(props, XR_EXT_HAND_TRACKING_EXTENSION_NAME)) {
            enabled.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
            handsAvailable_ = true;
            if (hasExtension(props, XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME)) {
                enabled.push_back(XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME);
                aimAvailable_ = true;
            }
        } else {
            LOGI("no %s - pinch input unavailable", XR_EXT_HAND_TRACKING_EXTENSION_NAME);
        }

        XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        androidInfo.applicationVM = g_vm;
        androidInfo.applicationActivity = g_activity;

        XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
        ci.next = &androidInfo;
        ci.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
        ci.enabledExtensionNames = enabled.data();
        strcpy(ci.applicationInfo.applicationName, "QuestTime VR");
        ci.applicationInfo.applicationVersion = 1;
        strcpy(ci.applicationInfo.engineName, "none");
        ci.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

        XR_TRY(xrCreateInstance(&ci, &instance_), "xrCreateInstance");
        return true;
    }

    bool getSystem() {
        XrSystemGetInfo info0{XR_TYPE_SYSTEM_GET_INFO};
        (void)info0;
        XrSystemGetInfo info{XR_TYPE_SYSTEM_GET_INFO};
        info.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XR_TRY(xrGetSystem(instance_, &info, &system_), "xrGetSystem");

        // Required before xrCreateSession, even though we ignore the result.
        PFN_xrGetOpenGLESGraphicsRequirementsKHR getReq = nullptr;
        XR_TRY(xrGetInstanceProcAddr(instance_, "xrGetOpenGLESGraphicsRequirementsKHR",
                                     reinterpret_cast<PFN_xrVoidFunction *>(&getReq)),
               "xrGetInstanceProcAddr(GraphicsRequirements)");
        XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        XR_TRY(getReq(instance_, system_, &req), "xrGetOpenGLESGraphicsRequirementsKHR");

        // Ask the system whether it actually supports hand tracking, rather than
        // inferring it from the extension merely being advertised.
        XrSystemHandTrackingPropertiesEXT handProps{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};
        XrSystemProperties sysProps{XR_TYPE_SYSTEM_PROPERTIES};
        sysProps.next = handsAvailable_ ? &handProps : nullptr;
        if (XR_SUCCEEDED(xrGetSystemProperties(instance_, system_, &sysProps))) {
            LOGI("system '%s' supportsHandTracking=%d maxSwapchain=%ux%u maxLayers=%u",
                 sysProps.systemName, handProps.supportsHandTracking ? 1 : 0,
                 sysProps.graphicsProperties.maxSwapchainImageWidth,
                 sysProps.graphicsProperties.maxSwapchainImageHeight,
                 sysProps.graphicsProperties.maxLayerCount);
            maxSwapW_ = sysProps.graphicsProperties.maxSwapchainImageWidth;
            maxSwapH_ = sysProps.graphicsProperties.maxSwapchainImageHeight;
            if (handsAvailable_ && !handProps.supportsHandTracking) {
                LOGI("system reports no hand tracking - disabling pinch");
                handsAvailable_ = false;
            }
        }

        uint32_t bm = 0;
        xrEnumerateEnvironmentBlendModes(instance_, system_,
                                         XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                         0, &bm, nullptr);
        std::vector<XrEnvironmentBlendMode> modes(bm);
        if (bm) {
            xrEnumerateEnvironmentBlendModes(instance_, system_,
                                             XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                             bm, &bm, modes.data());
        }
        std::string ms;
        for (auto m : modes) ms += std::to_string(static_cast<int>(m)) + " ";
        LOGI("environment blend modes (1=OPAQUE 2=ADDITIVE 3=ALPHA_BLEND): %s", ms.c_str());
        blendMode_ = modes.empty() ? XR_ENVIRONMENT_BLEND_MODE_OPAQUE : modes[0];
        return true;
    }

    bool createSession() {
        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = egl_.display;
        binding.config = egl_.config;
        binding.context = egl_.context;

        XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
        ci.next = &binding;
        ci.systemId = system_;
        XR_TRY(xrCreateSession(instance_, &ci, &session_), "xrCreateSession");

        XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        space.poseInReferenceSpace.orientation.w = 1.0f;
        XR_TRY(xrCreateReferenceSpace(session_, &space, &space_), "xrCreateReferenceSpace");
        setupHands();
        setupControllers();
        return true;
    }

    // Left-hand pinch, used to raise the menu. Failures here are never fatal - the
    // panorama must keep working whether or not hands are tracked.
    /**
     * Thumbstick turning. Bound on both hands to one action with subaction paths, so
     * either controller works and neither has to be the "main" one.
     *
     * Every failure here is survivable - the panorama does not need input - so none
     * of it is fatal.
     */
    void setupControllers() {
        XrActionSetCreateInfo asci{XR_TYPE_ACTION_SET_CREATE_INFO};
        strcpy(asci.actionSetName, "viewer");
        strcpy(asci.localizedActionSetName, "Viewer");
        if (!xrOk(xrCreateActionSet(instance_, &asci, &actionSet_), "xrCreateActionSet")) return;

        xrStringToPath(instance_, "/user/hand/left", &handPaths_[0]);
        xrStringToPath(instance_, "/user/hand/right", &handPaths_[1]);

        XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(aci.actionName, "turn");
        strcpy(aci.localizedActionName, "Turn");
        aci.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
        aci.countSubactionPaths = 2;
        aci.subactionPaths = handPaths_;
        if (!xrOk(xrCreateAction(actionSet_, &aci, &turnAction_), "xrCreateAction")) return;

        XrPath profile = XR_NULL_PATH, left = XR_NULL_PATH, right = XR_NULL_PATH;
        xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller", &profile);
        xrStringToPath(instance_, "/user/hand/left/input/thumbstick", &left);
        xrStringToPath(instance_, "/user/hand/right/input/thumbstick", &right);
        XrActionSuggestedBinding binds[] = {{turnAction_, left}, {turnAction_, right}};
        XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        sb.interactionProfile = profile;
        sb.countSuggestedBindings = 2;
        sb.suggestedBindings = binds;
        if (!xrOk(xrSuggestInteractionProfileBindings(instance_, &sb),
                  "xrSuggestInteractionProfileBindings")) return;

        XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        ai.countActionSets = 1;
        ai.actionSets = &actionSet_;
        if (!xrOk(xrAttachSessionActionSets(session_, &ai), "xrAttachSessionActionSets")) return;

        controllersReady_ = true;
        LOGI("controllers ready - flick either thumbstick to turn %.0f degrees", kTurnDegrees);
    }

    void updateTurn() {
        if (!controllersReady_) return;
        XrActiveActionSet active{actionSet_, XR_NULL_PATH};
        XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
        si.countActiveActionSets = 1;
        si.activeActionSets = &active;
        // Returns a success code, not an error, when the session is unfocused.
        if (XR_FAILED(xrSyncActions(session_, &si))) return;

        float x = 0.0f;
        for (int h = 0; h < 2; ++h) {
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = turnAction_;
            gi.subactionPath = handPaths_[h];
            XrActionStateVector2f st{XR_TYPE_ACTION_STATE_VECTOR2F};
            if (XR_SUCCEEDED(xrGetActionStateVector2f(session_, &gi, &st)) && st.isActive) {
                if (fabsf(st.currentState.x) > fabsf(x)) x = st.currentState.x;
            }
        }

        if (turnArmed_ && fabsf(x) > kTurnEngage) {
            const float step = kTurnDegrees * static_cast<float>(M_PI) / 180.0f;
            // Pushing right turns you right, and on this layer's pose that is a
            // *positive* yaw. Reasoning it out the other way - "you turn right, so
            // the world swings left" - is what put it in backwards; the pose rotates
            // with you, not against you. Confirmed in the headset.
            yaw_ += (x > 0.0f ? step : -step);
            turnArmed_ = false;
            LOGI("turn %s, %.0f degrees from where you started",
                 x > 0.0f ? "right" : "left",
                 yaw_ * 180.0f / static_cast<float>(M_PI));
        } else if (!turnArmed_ && fabsf(x) < kTurnRelease) {
            turnArmed_ = true;
        }
    }

    void setupHands() {
        if (!handsAvailable_) return;
        if (XR_FAILED(xrGetInstanceProcAddr(instance_, "xrCreateHandTrackerEXT",
                reinterpret_cast<PFN_xrVoidFunction *>(&createHandTracker_))) ||
            XR_FAILED(xrGetInstanceProcAddr(instance_, "xrLocateHandJointsEXT",
                reinterpret_cast<PFN_xrVoidFunction *>(&locateHandJoints_))) ||
            XR_FAILED(xrGetInstanceProcAddr(instance_, "xrDestroyHandTrackerEXT",
                reinterpret_cast<PFN_xrVoidFunction *>(&destroyHandTracker_)))) {
            LOGI("hand tracking entry points unavailable");
            handsAvailable_ = false;
            return;
        }
        XrHandTrackerCreateInfoEXT ci{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
        ci.hand = XR_HAND_LEFT_EXT;
        ci.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
        XrResult r = createHandTracker_(session_, &ci, &leftHand_);
        if (XR_FAILED(r)) {
            LOGI("xrCreateHandTrackerEXT failed (%d) - continuing without hands",
                 static_cast<int>(r));
            handsAvailable_ = false;
            leftHand_ = XR_NULL_HANDLE;
            return;
        }
        LOGI("left hand tracker ready - pinch to test");
    }

    // Thumb tip to index tip, with hysteresis so a held pinch does not chatter.
    void updatePinch(XrTime time) {
        if (!handsAvailable_ || leftHand_ == XR_NULL_HANDLE) return;

        XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT];
        XrHandJointLocationsEXT locs{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
        locs.jointCount = XR_HAND_JOINT_COUNT_EXT;
        locs.jointLocations = joints;

        // Meta's own pinch signal, independent of whether joint poses arrive.
        XrHandTrackingAimStateFB aim{XR_TYPE_HAND_TRACKING_AIM_STATE_FB};
        if (aimAvailable_) locs.next = &aim;

        XrHandJointsLocateInfoEXT li{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};
        li.baseSpace = space_;
        li.time = time;
        XrResult hr = locateHandJoints_(leftHand_, &li, &locs);
        handPolls_++;
        if (XR_FAILED(hr) || !locs.isActive) {
            if (handPolls_ % 150 == 0) {
                LOGI("hand: locate=%d isActive=%d (hands not being tracked - are you "
                     "holding controllers, or are your hands out of camera view?)",
                     static_cast<int>(hr), locs.isActive ? 1 : 0);
            }
            return;
        }

        const XrHandJointLocationEXT &thumb = joints[XR_HAND_JOINT_THUMB_TIP_EXT];
        const XrHandJointLocationEXT &index = joints[XR_HAND_JOINT_INDEX_TIP_EXT];

        const float dx = thumb.pose.position.x - index.pose.position.x;
        const float dy = thumb.pose.position.y - index.pose.position.y;
        const float dz = thumb.pose.position.z - index.pose.position.z;
        const float d = sqrtf(dx * dx + dy * dy + dz * dz);

        // Log before any guard, so a hand that is present but not fully tracked
        // still shows up rather than vanishing down a silent early return.
        const bool aimValid =
            aimAvailable_ && (aim.status & XR_HAND_TRACKING_AIM_VALID_BIT_FB) != 0;
        const bool aimPinch =
            aimValid && (aim.status & XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB) != 0;

        if (handPolls_ % 150 == 0) {
            LOGI("hand: thumbFlags=0x%llx indexFlags=0x%llx gap=%.1f mm | "
                 "aimExt=%d aimStatus=0x%llx aimValid=%d aimPinch=%d strength=%.2f",
                 static_cast<unsigned long long>(thumb.locationFlags),
                 static_cast<unsigned long long>(index.locationFlags), d * 1000.0f,
                 aimAvailable_ ? 1 : 0,
                 static_cast<unsigned long long>(aim.status),
                 aimValid ? 1 : 0, aimPinch ? 1 : 0, aim.pinchStrengthIndex);
        }

        // Prefer Meta's aim bit when it is valid; fall back to raw joint distance.
        if (aimValid) {
            if (aimPinch != pinching_) {
                pinching_ = aimPinch;
                if (pinching_) {
                    pinchCount_++;
                    LOGI("LEFT PINCH #%llu (via aim)",
                         static_cast<unsigned long long>(pinchCount_));
                } else {
                    LOGI("left pinch released (via aim)");
                }
            }
            return;
        }

        // POSITION_VALID is enough; insisting on POSITION_TRACKED as well rejects
        // perfectly usable hands whose pose the runtime is estimating.
        const XrSpaceLocationFlags need = XR_SPACE_LOCATION_POSITION_VALID_BIT;
        if ((thumb.locationFlags & need) != need) return;
        if ((index.locationFlags & need) != need) return;

        const float kOn = 0.022f;   // 2.2 cm - fingers touching
        const float kOff = 0.035f;  // 3.5 cm - clearly apart
        if (!pinching_ && d < kOn) {
            pinching_ = true;
            pinchCount_++;
            LOGI("LEFT PINCH #%llu (gap %.1f mm)",
                 static_cast<unsigned long long>(pinchCount_), d * 1000.0f);
        } else if (pinching_ && d > kOff) {
            pinching_ = false;
            LOGI("left pinch released (gap %.1f mm)", d * 1000.0f);
        }
    }

    bool createSwapchain(const Pano &pano) {
        uint32_t formatCount = 0;
        XR_TRY(xrEnumerateSwapchainFormats(session_, 0, &formatCount, nullptr),
               "xrEnumerateSwapchainFormats");
        std::vector<int64_t> formats(formatCount);
        XR_TRY(xrEnumerateSwapchainFormats(session_, formatCount, &formatCount, formats.data()),
               "xrEnumerateSwapchainFormats");

        int64_t chosen = 0;
        for (int64_t f : formats) if (f == GL_SRGB8_ALPHA8) { chosen = f; break; }
        if (chosen == 0) for (int64_t f : formats) if (f == GL_RGBA8) { chosen = f; break; }
        if (chosen == 0 && !formats.empty()) chosen = formats[0];
        if (chosen == 0) { setError("No usable swapchain format"); return false; }

        GLint maxTex = 0;
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
        LOGI("GL_MAX_TEXTURE_SIZE=%d, panorama %dx%d", maxTex, pano.width, pano.height);
        if ((maxTex > 0 && (pano.width > maxTex || pano.height > maxTex)) ||
            (maxSwapW_ && static_cast<uint32_t>(pano.width) > maxSwapW_) ||
            (maxSwapH_ && static_cast<uint32_t>(pano.height) > maxSwapH_)) {
            char buf[192];
            snprintf(buf, sizeof(buf),
                     "Panorama %dx%d exceeds this device's limits (texture %d, swapchain %ux%u)",
                     pano.width, pano.height, maxTex, maxSwapW_, maxSwapH_);
            setError(buf);
            LOGE("%s", buf);
            return false;
        }

        swWidth_ = pano.cube ? pano.faceSize : pano.width;
        swHeight_ = pano.cube ? pano.faceSize : pano.height;

        if (pano.cube && !cubeAvailable_) {
            const char *noCube = "This runtime has no cube layer support, so cubic "
                                "panoramas cannot be shown.";
            setError(noCube);
            LOGE("%s", noCube);
            return false;
        }

        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        ci.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        ci.format = chosen;
        ci.sampleCount = 1;
        ci.width = swWidth_;
        ci.height = swHeight_;
        ci.faceCount = pano.cube ? 6 : 1;   // a cube swapchain is a real cubemap
        ci.arraySize = 1;
        ci.mipCount = 1;
        XR_TRY(xrCreateSwapchain(session_, &ci, &swapchain_), "xrCreateSwapchain");

        uint32_t imageCount = 0;
        XR_TRY(xrEnumerateSwapchainImages(swapchain_, 0, &imageCount, nullptr),
               "xrEnumerateSwapchainImages");
        images_.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        XR_TRY(xrEnumerateSwapchainImages(
                   swapchain_, imageCount, &imageCount,
                   reinterpret_cast<XrSwapchainImageBaseHeader *>(images_.data())),
               "xrEnumerateSwapchainImages");
        filled_.assign(imageCount, 0);

        LOGI("swapchain %dx%d, %u images, format 0x%llx", swWidth_, swHeight_, imageCount,
             static_cast<unsigned long long>(chosen));
        return true;
    }

    // Upload the panorama into one swapchain image. Called the first time each
    // image index comes round; the content never changes after that.
    // Cubic upload. Faces arrive already in OpenGL order and already mirrored -
    // Qtvr.toGlOrder does that, so the geometry lives next to the code that reasons
    // about it. Here we just hand each face to its cubemap slot.
    void uploadCube(uint32_t index) {
        const int n = swWidth_;
        const size_t faceBytes = static_cast<size_t>(n) * n * 4;
        glBindTexture(GL_TEXTURE_CUBE_MAP, images_[index].image);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        for (int f = 0; f < 6; ++f) {
            glTexSubImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, 0, 0, 0, n, n,
                            GL_RGBA, GL_UNSIGNED_BYTE,
                            pano_->rgba.data() + static_cast<size_t>(f) * faceBytes);
        }
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
        glFinish();
        LOGI("uploaded cube image %u: %dx%d per face, glErr=0x%04x", index, n, n, glGetError());
    }

    void uploadImage(uint32_t index) {
        if (pano_->cube) { uploadCube(index); return; }
        glBindTexture(GL_TEXTURE_2D, images_[index].image);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, swWidth_, swHeight_,
                        GL_RGBA, GL_UNSIGNED_BYTE, pano_->rgba.data());
        GLenum err = glGetError();
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        // Read two pixels straight back out of the texture. If these match the
        // decoder's values the upload is good and any blackness is the layer's
        // fault, not the texture's.
        GLuint fbo = 0;
        GLubyte a[4] = {0, 0, 0, 0}, b[4] = {0, 0, 0, 0};
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               images_[index].image, 0);
        GLenum fb = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (fb == GL_FRAMEBUFFER_COMPLETE) {
            glReadPixels(0, 0, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, a);
            glReadPixels(swWidth_ / 2, swHeight_ / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, b);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &fbo);
        glBindTexture(GL_TEXTURE_2D, 0);
        glFinish();

        LOGI("uploaded image %u (tex=%u) glErr=0x%04x fbo=0x%04x "
             "readback (0,0)=[%d,%d,%d] (mid)=[%d,%d,%d]",
             index, images_[index].image, err, fb,
             a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    void handleStateChange(const XrEventDataSessionStateChanged &ev) {
        state_ = ev.state;
        switch (state_) {
            case XR_SESSION_STATE_READY: {
                XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
                begin.primaryViewConfigurationType =
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                if (xrOk(xrBeginSession(session_, &begin), "xrBeginSession")) {
                    sessionRunning_ = true;
                    LOGI("session started");
                }
                break;
            }
            case XR_SESSION_STATE_STOPPING:
                sessionRunning_ = false;
                xrEndSession(session_);
                LOGI("session stopped");
                break;
            case XR_SESSION_STATE_EXITING:
            case XR_SESSION_STATE_LOSS_PENDING:
                g_quit = true;
                break;
            default:
                break;
        }
    }

    void pollEvents() {
        for (;;) {
            XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
            XrResult r = xrPollEvent(instance_, &ev);
            if (r != XR_SUCCESS) break;
            if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                handleStateChange(reinterpret_cast<const XrEventDataSessionStateChanged &>(ev));
            } else if (ev.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
                g_quit = true;
            }
        }
    }

    void loop(const Pano &pano) {
        uint64_t frames = 0, rendered = 0;
        // radius 0 means an infinite cylinder, which is what a panorama wants (no
        // parallax as you move). Overridable for debugging without a rebuild:
        //   adb shell setprop debug.questtime.radius 20
        // Spec says radius 0 means an infinite cylinder, but this runtime draws
        // nothing for it - a quad layer with the same texture rendered fine while
        // the radius-0 cylinder stayed black. Any radius gives identical angular
        // geometry (it cancels out of atan(centralAngle / 2*aspectRatio)), so pick
        // one far enough away that head movement produces no useful parallax.
        // Far enough away to be effectively at infinity.
        //
        // 50 m worked, but the polar cap is a flat quad at radius*tan(halfV) while
        // the panorama is a cylinder at radius, so the two meet at the rim with a
        // step in *depth*. At 50 m each eye's 32 mm offset makes that step resolve
        // differently per eye - a stereo discontinuity, visible as a hairline in the
        // headset and completely absent from a mono cast, which is exactly the
        // symptom reported. Pushing everything out to 500 m shrinks the disparity by
        // an order of magnitude and puts the panorama where it belongs: at infinity,
        // with no parallax as you lean. The angular geometry is untouched - radius
        // cancels out of atan(centralAngle / (2 * aspectRatio)).
        float radius = 500.0f;
        int arcs = 4;
        bool probe = false;
        // Angular overlap between neighbouring arcs, in thousandths of a degree.
        // Arcs that abut exactly put two layer edges on the same line, and the
        // compositor's edge handling leaves a hairline there. A sliver of overlap
        // means each edge falls over its neighbour's interior instead of over the
        // background. The duplicated content spans well under a tenth of a degree.
        int bleedMilliDeg = 80;
        char propBuf[PROP_VALUE_MAX] = {0};
        if (__system_property_get("debug.questtime.radius", propBuf) > 0) {
            radius = strtof(propBuf, nullptr);
        }
        if (__system_property_get("debug.questtime.arcs", propBuf) > 0) {
            arcs = atoi(propBuf);
        }
        if (__system_property_get("debug.questtime.probe", propBuf) > 0) {
            probe = atoi(propBuf) != 0;
        }
        if (__system_property_get("debug.questtime.bleed", propBuf) > 0) {
            bleedMilliDeg = atoi(propBuf);
        }
        if (bleedMilliDeg < 0) bleedMilliDeg = 0;
        const float bleed = static_cast<float>(bleedMilliDeg) * 0.001f * float(M_PI) / 180.0f;
        if (arcs < 1) arcs = 1;
        if (arcs > 16) arcs = 16;
        if (arcs > swWidth_) arcs = swWidth_;

        // centralAngle is valid over [0, 2*pi) - half open - and a full turn passed
        // as exactly 2*pi renders nothing while xrEndFrame still reports success.
        //
        // But that limit applies to each *layer*, and with four arcs no layer is
        // anywhere near 2*pi. Clamping the total instead left a wedge of about
        // 0.0098 degrees unclaimed, directly behind the viewer, where the first and
        // last arc should have met. Sub-pixel on paper, and invisible in a cast
        // stream, but a hard-edged sliver of the black background at full headset
        // resolution - reported as "a thin black line right behind me", which is
        // exactly what it was. So only back off when a single arc really does have
        // to carry the whole turn.
        const float kMaxSingleArc = 6.2831f;
        const float centralAngle =
            (arcs == 1 && pano.centralAngle >= kMaxSingleArc) ? kMaxSingleArc
                                                              : pano.centralAngle;
        LOGI("layer: centralAngle=%.6f (from %.6f) aspect=%.4f radius=%.2f arcs=%d "
             "perArc=%.6f bleed=%.4fdeg probe=%d",
             centralAngle, pano.centralAngle, pano.aspect, radius, arcs,
             centralAngle / arcs, bleedMilliDeg * 0.001, probe ? 1 : 0);
        const double halfFovDeg =
            atan(centralAngle / (2.0 * pano.aspect)) * 180.0 / M_PI;
        while (!g_quit) {
            pollEvents();
            if (!sessionRunning_) {
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }

            XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
            XrFrameState frameState{XR_TYPE_FRAME_STATE};
            if (!xrOk(xrWaitFrame(session_, &waitInfo, &frameState), "xrWaitFrame")) break;

            XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
            if (!xrOk(xrBeginFrame(session_, &beginInfo), "xrBeginFrame")) break;

            updatePinch(frameState.predictedDisplayTime);
            updateTurn();

            // Drive the swapchain properly every frame rather than filling it once
            // up front: the runtime presents the image released for this frame.
            uint32_t index = 0;
            XrSwapchainImageAcquireInfo acq{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            if (!xrOk(xrAcquireSwapchainImage(swapchain_, &acq, &index),
                      "xrAcquireSwapchainImage")) break;
            XrSwapchainImageWaitInfo wait2{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            wait2.timeout = XR_INFINITE_DURATION;
            if (!xrOk(xrWaitSwapchainImage(swapchain_, &wait2), "xrWaitSwapchainImage")) break;
            if (index < filled_.size() && !filled_[index]) {
                uploadImage(index);
                filled_[index] = 1;
            }
            XrSwapchainImageReleaseInfo rel{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            if (!xrOk(xrReleaseSwapchainImage(swapchain_, &rel),
                      "xrReleaseSwapchainImage")) break;

            // One near-360 degree arc makes this runtime blank half the cylinder
            // depending on which way you face the seam. Splitting the same texture
            // into N arcs of 2*pi/N keeps every arc comfortably small. Each arc
            // points at its own slice of the texture, so nothing is resampled.
            std::vector<XrCompositionLayerCylinderKHR> cyls(arcs);
            std::vector<const XrCompositionLayerBaseHeader *> layers;
            layers.reserve(arcs + 3);

            if (pano.cube) {
                XrCompositionLayerCubeKHR cubeLayer{XR_TYPE_COMPOSITION_LAYER_CUBE_KHR};
                cubeLayer.layerFlags = 0;
                cubeLayer.space = space_;
                cubeLayer.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                cubeLayer.swapchain = swapchain_;
                cubeLayer.imageArrayIndex = 0;
                cubeLayer.orientation =
                    {0.0f, sinf(yaw_ * 0.5f), 0.0f, cosf(yaw_ * 0.5f)};

                const XrCompositionLayerBaseHeader *cubeLayers[] = {
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&cubeLayer),
                };
                XrFrameEndInfo cubeEnd{XR_TYPE_FRAME_END_INFO};
                cubeEnd.displayTime = frameState.predictedDisplayTime;
                cubeEnd.environmentBlendMode = blendMode_;
                cubeEnd.layerCount = frameState.shouldRender ? 1 : 0;
                cubeEnd.layers = frameState.shouldRender ? cubeLayers : nullptr;
                if (!xrOk(xrEndFrame(session_, &cubeEnd), "xrEndFrame")) break;
                frames++;
                if (frameState.shouldRender) rendered++;
                if (frames % 720 == 0) {
                    LOGI("frames=%llu rendered=%llu (cube layer)",
                         static_cast<unsigned long long>(frames),
                         static_cast<unsigned long long>(rendered));
                }
                continue;
            }

            // A cylinder reaches +/-90 degrees only in the limit, so however many
            // rows the texture has there is always an open disc at each pole. Plug
            // each one with a quad laid across the cylinder's rim.
            //
            // These go into the layer list BEFORE the arcs. Composition layers are
            // composited in submission order rather than by depth, so the arcs paint
            // over the caps and each cap shows only through its hole - bounded by the
            // rim circle instead of by its own rectangular edge.
            //
            // The gradient's outermost row has already converged to a single flat
            // colour, so each cap samples one texel of it - no extra texture, and
            // the seam matches by construction.
            const float halfV = atanf(centralAngle / (2.0f * pano.aspect));
            const float rimY = radius * tanf(halfV);
            const float capHalf = radius * 2.0f;    // generous; the arcs cover the excess

            XrCompositionLayerQuad caps[2];
            for (int i = 0; i < 2; ++i) {
                const bool top = (i == 0);
                XrCompositionLayerQuad &q = caps[i];
                q = XrCompositionLayerQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
                q.layerFlags = 0;
                q.space = space_;
                q.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                q.subImage.swapchain = swapchain_;
                // Texture is uploaded flipped: image top row sits at the highest
                // texture row, image bottom row at row 0.
                q.subImage.imageRect.offset = {swWidth_ / 2, top ? swHeight_ - 2 : 0};
                q.subImage.imageRect.extent = {32, 2};
                q.subImage.imageArrayIndex = 0;
                // A quad faces the +Z of its pose; rotate +/-90 degrees about X so
                // the ceiling cap looks down and the floor cap looks up.
                const float sign = top ? 1.0f : -1.0f;
                q.pose.orientation = {sign * 0.70710678f, 0.0f, 0.0f, 0.70710678f};
                q.pose.position = {0.0f, top ? rimY : -rimY, 0.0f};
                q.size = {capHalf * 2.0f, capHalf * 2.0f};
                layers.push_back(
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&q));
            }

            // Slice boundaries as exact texel columns. A width that does not
            // divide evenly by `arcs` used to reduce the arc count until it did,
            // which for an odd width landed back on arcs = 1 - the single
            // near-360 degree arc that blanks half the cylinder. Instead let the
            // slices differ by a texel and give each one the angle its own width
            // earns. The vertical extent is unaffected: the runtime derives it
            // from centralAngle / (2 * aspectRatio), and scaling both by the same
            // w/W leaves that ratio exactly where it was, so every arc still ends
            // at the same horizon however the columns fall.
            for (int i = 0; i < arcs; ++i) {
                const int32_t x0 = static_cast<int32_t>(
                    static_cast<int64_t>(i) * swWidth_ / arcs);
                const int32_t x1 = static_cast<int32_t>(
                    static_cast<int64_t>(i + 1) * swWidth_ / arcs);
                const int32_t sliceW = x1 - x0;

                // Where this slice's centre sits relative to straight ahead.
                // Positive is to the viewer's right.
                const float phi =
                    (static_cast<float>(x0 + x1) * 0.5f) / static_cast<float>(swWidth_) - 0.5f;
                const float toRight = phi * centralAngle;
                // A positive rotation about +Y swings -Z to the left, so negate.
                const float theta = -toRight + yaw_;
                const float sliceFrac =
                    static_cast<float>(sliceW) / static_cast<float>(swWidth_);

                XrCompositionLayerCylinderKHR &c = cyls[i];
                c = XrCompositionLayerCylinderKHR{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
                c.layerFlags = 0;
                c.space = space_;
                c.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                c.subImage.swapchain = swapchain_;
                c.subImage.imageRect.offset = {x0, 0};
                c.subImage.imageRect.extent = {sliceW, swHeight_};
                c.subImage.imageArrayIndex = 0;
                c.pose.orientation = {0.0f, sinf(theta * 0.5f), 0.0f, cosf(theta * 0.5f)};
                c.pose.position = {0.0f, 0.0f, 0.0f};
                c.radius = radius;
                // Grow each arc about its own centre by `bleed` so neighbours
                // overlap. aspectRatio scales by the same factor, which leaves
                // centralAngle / aspectRatio - and therefore the horizon - exactly
                // where it was.
                const float baseAngle = centralAngle * sliceFrac;
                const float grow = baseAngle > 0.0f ? (baseAngle + bleed) / baseAngle : 1.0f;
                c.centralAngle = baseAngle * grow;
                c.aspectRatio = pano.aspect * sliceFrac * grow;
                layers.push_back(
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&c));
            }

            XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
            if (probe) {
                quad.layerFlags = 0;
                quad.space = space_;
                quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                quad.subImage.swapchain = swapchain_;
                quad.subImage.imageRect.offset = {0, 0};
                quad.subImage.imageRect.extent = {swWidth_, swHeight_};
                quad.subImage.imageArrayIndex = 0;
                quad.pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
                quad.pose.position = {0.0f, 0.0f, -2.0f};
                quad.size = {2.0f, 2.0f / pano.aspect};
                layers.push_back(
                    reinterpret_cast<const XrCompositionLayerBaseHeader *>(&quad));
            }

            XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
            endInfo.displayTime = frameState.predictedDisplayTime;
            endInfo.environmentBlendMode = blendMode_;
            endInfo.layerCount =
                frameState.shouldRender ? static_cast<uint32_t>(layers.size()) : 0;
            endInfo.layers = frameState.shouldRender ? layers.data() : nullptr;
            if (!xrOk(xrEndFrame(session_, &endInfo), "xrEndFrame")) break;

            frames++;
            if (frameState.shouldRender) rendered++;
            if (frames % 720 == 0) {   // roughly every 10s
                LOGI("frames=%llu rendered=%llu (cylinder layer, +/-%.2f deg vertical)",
                     static_cast<unsigned long long>(frames),
                     static_cast<unsigned long long>(rendered), halfFovDeg);
            }
        }
        LOGI("loop ended after %llu frames (%llu rendered)",
             static_cast<unsigned long long>(frames),
             static_cast<unsigned long long>(rendered));
    }

    void shutdown() {
        if (actionSet_ != XR_NULL_HANDLE) xrDestroyActionSet(actionSet_);
        actionSet_ = XR_NULL_HANDLE;
        controllersReady_ = false;
        if (leftHand_ != XR_NULL_HANDLE && destroyHandTracker_) destroyHandTracker_(leftHand_);
        if (swapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(swapchain_);
        if (space_ != XR_NULL_HANDLE) xrDestroySpace(space_);
        if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
        if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
        leftHand_ = XR_NULL_HANDLE;
        swapchain_ = XR_NULL_HANDLE;
        space_ = XR_NULL_HANDLE;
        session_ = XR_NULL_HANDLE;
        instance_ = XR_NULL_HANDLE;
        pano_ = nullptr;
        egl_.destroy();
    }
};

// A relaunch can arrive while the previous session's detached thread is still
// winding down. Refusing outright left Kotlin showing "entering VR" over a black
// screen forever, because nothing was running to report a failure. Ask the old
// session to stop and give it a moment instead.
bool claimSession() {
    if (!g_running.exchange(true)) return true;
    LOGI("a previous session is still running - asking it to stop");
    g_quit = true;
    for (int i = 0; i < 300 && g_running.load(); ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if (g_running.exchange(true)) {
        setError("The previous panorama is still shutting down. Try again in a moment.");
        return false;
    }
    return true;
}

// Runs on the render thread, once the viewer has finished with the Activity.
void releaseActivity(JNIEnv *env) {
    if (g_activity != nullptr) {
        env->DeleteGlobalRef(g_activity);
        g_activity = nullptr;
    }
}

}  // namespace

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_questtime_vr_VrActivity_nativeStart(
    JNIEnv *env, jobject thiz, jobject buffer, jint width, jint height,
    jfloat centralAngle, jfloat aspectRatio) {

    clearError();
    if (!claimSession()) return JNI_FALSE;
    g_quit = false;

    auto *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (src == nullptr || capacity < static_cast<jlong>(width) * height * 4) {
        setError("Pixel buffer was not a direct ByteBuffer of the expected size");
        LOGE("%s", lastError().c_str());
        g_running = false;
        return JNI_FALSE;
    }

    auto pano = std::make_shared<Pano>();
    // Flip vertically. The decoder hands us rows top-down, but a GL texture's row 0
    // is its *bottom* row, and the compositor samples the subImage top-down - so an
    // unflipped upload shows the panorama upside down.
    {
        const size_t stride = static_cast<size_t>(width) * 4;
        pano->rgba.resize(stride * static_cast<size_t>(height));
        for (int y = 0; y < height; ++y) {
            memcpy(pano->rgba.data() + static_cast<size_t>(y) * stride,
                   src + static_cast<size_t>(height - 1 - y) * stride, stride);
        }
    }
    pano->width = width;
    pano->height = height;
    pano->centralAngle = centralAngle;
    pano->aspect = aspectRatio;

    if (g_activity != nullptr) env->DeleteGlobalRef(g_activity);
    g_activity = env->NewGlobalRef(thiz);

    std::thread([pano]() {
        JNIEnv *threadEnv = nullptr;
        JavaVMAttachArgs args{JNI_VERSION_1_6, "QuestTimeVR", nullptr};
        g_vm->AttachCurrentThread(&threadEnv, &args);
        LOGI("starting viewer: %dx%d, centralAngle=%.4f aspect=%.4f",
             pano->width, pano->height, pano->centralAngle, pano->aspect);
        Viewer viewer;
        if (!viewer.run(*pano)) LOGE("viewer stopped: %s", lastError().c_str());
        releaseActivity(threadEnv);
        g_vm->DetachCurrentThread();
        g_running = false;
    }).detach();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_questtime_vr_VrActivity_nativeStartCube(
    JNIEnv *env, jobject thiz, jobject buffer, jint faceSize) {

    clearError();
    if (!claimSession()) return JNI_FALSE;
    g_quit = false;

    auto *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong needed = static_cast<jlong>(faceSize) * faceSize * 4 * 6;
    if (src == nullptr || capacity < needed) {
        setError("Cube buffer was not a direct ByteBuffer of the expected size");
        LOGE("%s", lastError().c_str());
        g_running = false;
        return JNI_FALSE;
    }

    auto pano = std::make_shared<Pano>();
    // No vertical flip here: cubemap faces use the opposite row convention to 2D
    // textures, so they upload as stored.
    pano->rgba.assign(src, src + needed);
    pano->cube = true;
    pano->faceSize = faceSize;
    pano->width = faceSize;
    pano->height = faceSize;

    if (g_activity != nullptr) env->DeleteGlobalRef(g_activity);
    g_activity = env->NewGlobalRef(thiz);

    std::thread([pano]() {
        JNIEnv *threadEnv = nullptr;
        JavaVMAttachArgs args{JNI_VERSION_1_6, "QuestTimeVR", nullptr};
        g_vm->AttachCurrentThread(&threadEnv, &args);
        LOGI("starting cubic viewer: 6 faces of %dx%d", pano->faceSize, pano->faceSize);
        Viewer viewer;
        if (!viewer.run(*pano)) LOGE("viewer stopped: %s", lastError().c_str());
        releaseActivity(threadEnv);
        g_vm->DetachCurrentThread();
        g_running = false;
    }).detach();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_questtime_vr_VrActivity_nativeStop(JNIEnv *, jobject) {
    g_quit = true;
}

JNIEXPORT jstring JNICALL
Java_com_questtime_vr_VrActivity_nativeLastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(lastError().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_questtime_vr_VrActivity_nativeIsRunning(JNIEnv *, jobject) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
