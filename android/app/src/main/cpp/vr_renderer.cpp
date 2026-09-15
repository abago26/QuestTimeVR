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

// The menu bar's pixels, drawn by Kotlin and handed over before the viewer starts.
// Global rather than carried on Pano because the bar outlives any one panorama -
// it is the same bar whichever file is open, and re-uploading it on every open
// would be work for nothing.
std::mutex g_menuMutex;
std::vector<uint8_t> g_menuPixels;
int g_menuW = 0, g_menuH = 0;
/** Kotlin drives visibility now; the button only asks Kotlin to change it. */
bool g_menuWanted = false;
/** True while the list is up: the thumbstick scrolls instead of turning. */
bool g_picking = false;
/** Bumped on every new bitmap, so the viewer knows to re-upload. */
uint64_t g_menuVersion = 0;
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

// What native tells Kotlin happened. Kotlin owns the meaning.
constexpr int kInputMenu   = 0;   // menu button / left pinch: open or close
constexpr int kInputSelect = 1;
constexpr int kInputInfo   = 2;
constexpr int kInputUp     = 3;
constexpr int kInputDown   = 4;

// The menu bar, in metres. A quad this wide at this distance subtends about 34
// degrees - readable without being a wall, and inside the comfortable focus range.
constexpr float kMenuDistance = 1.6f;
constexpr float kMenuWidth = 1.0f;
/** Asked for: 0.68 s each way. Long enough to read as a fade, short enough to obey. */
constexpr float kMenuFadeSeconds = 0.68f;

/** Wrap-around columns on each side of a cylindrical texture. */
constexpr int kApronColumns = 8;

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
    /** A rolled copy, only when the diagnostic property asks for one. */
    std::vector<uint8_t> rolled_;
    /** Scratch for the padded upload; released as soon as it is handed to GL. */
    std::vector<uint8_t> padded_;
    /** Columns of wrap-around copied onto each side. 0 for cubic. */
    int apron_ = 0;
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
    XrAction menuAction_ = XR_NULL_HANDLE;
    /** A and X: open the picker, then confirm what is highlighted. */
    XrAction selectAction_ = XR_NULL_HANDLE;
    /** B and Y: show what this file is. */
    XrAction infoAction_ = XR_NULL_HANDLE;
    bool selectArmed_ = true;
    bool infoArmed_ = true;
    bool scrollArmed_ = true;
    XrPath handPaths_[2] = {XR_NULL_PATH, XR_NULL_PATH};
    bool controllersReady_ = false;
    bool turnArmed_ = true;
    /** Accumulated snap-turn, applied to every layer's pose. */
    float yaw_ = 0.0f;

    // -- the menu bar -------------------------------------------------------
    // Head-locked rather than world-locked, which is why it needs no view pose:
    // a VIEW reference space already tracks the head, so a quad sitting at -Z in
    // that space is in front of you wherever you are looking. For something you
    // summon and dismiss that is the right behaviour anyway - a world-locked bar
    // would need finding again after a snap turn.
    XrSpace menuSpace_ = XR_NULL_HANDLE;
    /**
     * Where the panel was placed when it opened, in the world.
     *
     * Head-locked was wrong for something you read: it rides every small movement of
     * your head, so it never settles and you cannot look at a corner of it. Locked to
     * the world at the yaw you were facing when it appeared, it stays put and you can
     * look around it - and it is still in front of you, because it was placed in
     * front of you.
     */
    float menuYaw_ = 0.0f;
    /** 0 hidden, 1 fully present. Ramped rather than switched. */
    float menuAlpha_ = 0.0f;
    bool menuWasWanted_ = false;
    std::chrono::steady_clock::time_point lastFrameAt_{};
    XrSwapchain menuSwapchain_ = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> menuImages_;
    std::vector<uint8_t> menuPixels_;
    int menuW_ = 0, menuH_ = 0;
    /** Negotiated with the runtime for the panorama; the menu reuses it. */
    int64_t swapFormat_ = 0;
    bool menuUploaded_ = false;
    uint64_t menuVersion_ = 0;
    XrCompositionLayerColorScaleBiasKHR menuFade_{};
    bool colorScaleAvailable_ = false;
    bool menuArmed_ = true;
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
        // Optional, and the fade degrades to a hard cut without it rather than
        // failing - which is the right trade for an animation.
        if (hasExtension(props, XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME)) {
            enabled.push_back(XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME);
            colorScaleAvailable_ = true;
        } else {
            LOGI("no %s - the menu will appear and vanish without a fade",
                 XR_KHR_COMPOSITION_LAYER_COLOR_SCALE_BIAS_EXTENSION_NAME);
        }

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

        // The menu rides on the head. Not fatal if it fails - the panorama is the
        // point, and a viewer with no menu is still a viewer.
        // The same space the panorama uses. The panel used to ride a VIEW space,
        // which is head-locked and unreadable for anything longer than a word.
        XrReferenceSpaceCreateInfo view{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        view.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        view.poseInReferenceSpace.orientation.w = 1.0f;
        if (!xrOk(xrCreateReferenceSpace(session_, &view, &menuSpace_),
                  "xrCreateReferenceSpace(VIEW)")) {
            menuSpace_ = XR_NULL_HANDLE;
        }

        setupHands();
        setupControllers();
        return true;
    }

    /**
     * Give the menu bar its own swapchain, sized to the bitmap Kotlin drew.
     *
     * Separate from the panorama's: that one is a cylinder's worth of pixels and is
     * re-created per file, while this is a small RGBA strip that outlives any one
     * panorama. Sharing would mean re-uploading the menu every time a file opens.
     */
    bool ensureMenuSwapchain() {
        {
            std::lock_guard<std::mutex> lock(g_menuMutex);
            if (g_menuPixels.empty()) return false;   // Kotlin never sent one
            if (swapFormat_ == 0) return false;       // no format negotiated yet
            // Kotlin redraws on every keypress - a new highlight, a different file,
            // the list instead of the bar - so this is not a one-off upload the way
            // the panorama is. A version bump means take the new pixels.
            if (menuVersion_ != g_menuVersion) {
                menuVersion_ = g_menuVersion;
                const bool resized = (g_menuW != menuW_ || g_menuH != menuH_);
                menuPixels_ = g_menuPixels;
                menuW_ = g_menuW;
                menuH_ = g_menuH;
                menuUploaded_ = false;
                // The list is taller than the bar, and a swapchain's size is fixed at
                // creation, so a change of shape means a new one.
                if (resized && menuSwapchain_ != XR_NULL_HANDLE) {
                    xrDestroySwapchain(menuSwapchain_);
                    menuSwapchain_ = XR_NULL_HANDLE;
                    menuImages_.clear();
                }
            }
        }
        if (menuSwapchain_ != XR_NULL_HANDLE && menuUploaded_) return true;
        if (menuSwapchain_ != XR_NULL_HANDLE) { uploadMenu(); return menuUploaded_; }

        XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        ci.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        // The same format the panorama negotiated, rather than a hardcoded one:
        // a runtime need not offer GL_RGBA8 at all, and if it chose sRGB for the
        // panorama then the bar must match or it comes out at a different gamma.
        ci.format = swapFormat_;
        ci.sampleCount = 1;
        ci.width = menuW_;
        ci.height = menuH_;
        ci.faceCount = 1;
        ci.arraySize = 1;
        ci.mipCount = 1;
        if (!xrOk(xrCreateSwapchain(session_, &ci, &menuSwapchain_), "xrCreateSwapchain(menu)")) {
            menuSwapchain_ = XR_NULL_HANDLE;
            return false;
        }
        uint32_t count = 0;
        xrEnumerateSwapchainImages(menuSwapchain_, 0, &count, nullptr);
        menuImages_.assign(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(
            menuSwapchain_, count, &count,
            reinterpret_cast<XrSwapchainImageBaseHeader *>(menuImages_.data()));

        uploadMenu();
        LOGI("menu swapchain %dx%d, %u images", menuW_, menuH_, count);
        return menuUploaded_;
    }

    /**
     * Push the current bitmap into every swapchain image.
     *
     * Every image, not just the one about to be used: the compositor cycles them, so
     * filling one and releasing leaves the others holding the previous drawing, which
     * shows up as the highlight flickering between two rows.
     */
    void uploadMenu() {
        const uint32_t count = static_cast<uint32_t>(menuImages_.size());
        for (uint32_t i = 0; i < count; ++i) {
            uint32_t index = 0;
            XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            if (XR_FAILED(xrAcquireSwapchainImage(menuSwapchain_, &ai, &index))) break;
            XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            wi.timeout = XR_INFINITE_DURATION;
            xrWaitSwapchainImage(menuSwapchain_, &wi);
            glBindTexture(GL_TEXTURE_2D, menuImages_[index].image);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, menuW_, menuH_, GL_RGBA,
                            GL_UNSIGNED_BYTE, menuPixels_.data());
            glBindTexture(GL_TEXTURE_2D, 0);
            glFinish();
            XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(menuSwapchain_, &ri);
        }
        menuUploaded_ = true;
    }

    /**
     * The menu bar as a composition layer, or nullptr when there is nothing to show.
     *
     * Returns a pointer into [store] so the caller owns the lifetime - a layer
     * submitted to xrEndFrame has to outlive this call.
     */
    const XrCompositionLayerBaseHeader *menuLayer(XrCompositionLayerQuad &store) {
        bool wanted;
        { std::lock_guard<std::mutex> lock(g_menuMutex); wanted = g_menuWanted; }

        // Place it once, on the way in, at the yaw you are facing. After that it is
        // world-locked, so turning looks past it rather than dragging it along.
        if (wanted && !menuWasWanted_) menuYaw_ = yaw_;
        menuWasWanted_ = wanted;

        // Seconds since the last frame, measured rather than assumed: this runs at
        // whatever rate the compositor gives it, and a fade counted in frames would
        // be a different length on a different day.
        const auto now = std::chrono::steady_clock::now();
        float dt = 0.0f;
        if (lastFrameAt_.time_since_epoch().count() != 0) {
            dt = std::chrono::duration<float>(now - lastFrameAt_).count();
        }
        lastFrameAt_ = now;
        if (dt > 0.25f) dt = 0.25f;          // a long stall must not jump the fade

        const float step = dt / kMenuFadeSeconds;
        menuAlpha_ += wanted ? step : -step;
        if (menuAlpha_ > 1.0f) menuAlpha_ = 1.0f;
        if (menuAlpha_ < 0.0f) menuAlpha_ = 0.0f;

        if (menuAlpha_ <= 0.0f || menuSpace_ == XR_NULL_HANDLE) return nullptr;
        if (!ensureMenuSwapchain()) return nullptr;

        store = XrCompositionLayerQuad{XR_TYPE_COMPOSITION_LAYER_QUAD};
        // The bitmap has an alpha channel and its corners are transparent, so the
        // compositor has to be told to respect it or the bar arrives as a black slab.
        store.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
        store.space = menuSpace_;
        store.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
        store.subImage.swapchain = menuSwapchain_;
        store.subImage.imageRect.offset = {0, 0};
        store.subImage.imageRect.extent = {menuW_, menuH_};
        store.subImage.imageArrayIndex = 0;
        // Fade via the per-layer colour scale rather than by redrawing the bitmap:
        // it costs nothing, and the alternative is re-uploading a megabyte of RGBA
        // every frame for the length of the fade.
        menuFade_ = XrCompositionLayerColorScaleBiasKHR{
            XR_TYPE_COMPOSITION_LAYER_COLOR_SCALE_BIAS_KHR};
        menuFade_.colorScale = {1.0f, 1.0f, 1.0f, menuAlpha_};
        menuFade_.colorBias = {0.0f, 0.0f, 0.0f, 0.0f};
        if (colorScaleAvailable_) store.next = &menuFade_;
        // Face the yaw it was opened at, and sit that far along it.
        store.pose.orientation = {0.0f, sinf(menuYaw_ * 0.5f), 0.0f, cosf(menuYaw_ * 0.5f)};
        store.pose.position = {kMenuDistance * -sinf(menuYaw_), -0.25f,
                               kMenuDistance * -cosf(menuYaw_)};
        const float w = kMenuWidth;
        store.size = {w, w * static_cast<float>(menuH_) / static_cast<float>(menuW_)};
        return reinterpret_cast<const XrCompositionLayerBaseHeader *>(&store);
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

        XrActionCreateInfo mci{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(mci.actionName, "menu");
        strcpy(mci.localizedActionName, "Show or hide the menu");
        mci.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
        // No subaction paths: only the left controller carries a menu button an app
        // may bind. The right one's equivalent is the system button, reserved by
        // Horizon OS, and asking for it gets the binding rejected rather than shared.
        if (!xrOk(xrCreateAction(actionSet_, &mci, &menuAction_), "xrCreateAction menu")) return;

        XrActionCreateInfo sci{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(sci.actionName, "select");
        strcpy(sci.localizedActionName, "Choose a panorama");
        sci.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
        if (!xrOk(xrCreateAction(actionSet_, &sci, &selectAction_), "xrCreateAction select"))
            return;

        XrActionCreateInfo ici{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(ici.actionName, "info");
        strcpy(ici.localizedActionName, "What is this file");
        ici.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
        if (!xrOk(xrCreateAction(actionSet_, &ici, &infoAction_), "xrCreateAction info")) return;

        XrPath profile = XR_NULL_PATH, left = XR_NULL_PATH, right = XR_NULL_PATH;
        XrPath menu = XR_NULL_PATH;
        xrStringToPath(instance_, "/interaction_profiles/oculus/touch_controller", &profile);
        xrStringToPath(instance_, "/user/hand/left/input/thumbstick", &left);
        xrStringToPath(instance_, "/user/hand/right/input/thumbstick", &right);
        xrStringToPath(instance_, "/user/hand/left/input/menu/click", &menu);
        // A and X do the same thing, and so do B and Y: whichever hand the
        // controller is in, the near button opens the list and the far one explains
        // what you are looking at. Nobody should have to remember which is which.
        XrPath aClick = XR_NULL_PATH, xClick = XR_NULL_PATH;
        XrPath bClick = XR_NULL_PATH, yClick = XR_NULL_PATH;
        xrStringToPath(instance_, "/user/hand/right/input/a/click", &aClick);
        xrStringToPath(instance_, "/user/hand/left/input/x/click", &xClick);
        xrStringToPath(instance_, "/user/hand/right/input/b/click", &bClick);
        xrStringToPath(instance_, "/user/hand/left/input/y/click", &yClick);
        XrActionSuggestedBinding binds[] = {
            {turnAction_, left}, {turnAction_, right}, {menuAction_, menu},
            {selectAction_, aClick}, {selectAction_, xClick},
            {infoAction_, bClick}, {infoAction_, yClick}};
        XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
        sb.interactionProfile = profile;
        sb.countSuggestedBindings = 7;
        sb.suggestedBindings = binds;
        if (!xrOk(xrSuggestInteractionProfileBindings(instance_, &sb),
                  "xrSuggestInteractionProfileBindings")) return;

        XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
        ai.countActionSets = 1;
        ai.actionSets = &actionSet_;
        if (!xrOk(xrAttachSessionActionSets(session_, &ai), "xrAttachSessionActionSets")) return;

        controllersReady_ = true;
        LOGI("controllers ready - flick either thumbstick to turn %.0f degrees, "
             "left menu button shows the bar", kTurnDegrees);
    }

    void updateTurn() {
        if (!controllersReady_) return;
        XrActiveActionSet active{actionSet_, XR_NULL_PATH};
        XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
        si.countActiveActionSets = 1;
        si.activeActionSets = &active;
        // Returns a success code, not an error, when the session is unfocused.
        if (XR_FAILED(xrSyncActions(session_, &si))) return;

        float x = 0.0f, y = 0.0f;
        for (int h = 0; h < 2; ++h) {
            XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
            gi.action = turnAction_;
            gi.subactionPath = handPaths_[h];
            XrActionStateVector2f st{XR_TYPE_ACTION_STATE_VECTOR2F};
            if (XR_SUCCEEDED(xrGetActionStateVector2f(session_, &gi, &st)) && st.isActive) {
                if (fabsf(st.currentState.x) > fabsf(x)) x = st.currentState.x;
                if (fabsf(st.currentState.y) > fabsf(y)) y = st.currentState.y;
            }
        }

        bool picking;
        { std::lock_guard<std::mutex> lock(g_menuMutex); picking = g_picking; }

        // While the list is up the stick belongs to it. Turning and scrolling on the
        // same stick at the same time means every attempt to move the highlight also
        // swings the world, which is disorienting and makes diagonal flicks do two
        // things at once.
        if (picking) {
            turnArmed_ = true;
        } else if (turnArmed_ && fabsf(x) > kTurnEngage) {
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

        // Edge-triggered, same shape as the thumbstick: a held button must toggle
        // once, not once per frame.
        XrActionStateGetInfo mi{XR_TYPE_ACTION_STATE_GET_INFO};
        mi.action = menuAction_;
        XrActionStateBoolean ms{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (XR_SUCCEEDED(xrGetActionStateBoolean(session_, &mi, &ms)) && ms.isActive) {
            if (menuArmed_ && ms.currentState) {
                notifyInput(kInputMenu);
                menuArmed_ = false;
            } else if (!menuArmed_ && !ms.currentState) {
                menuArmed_ = true;
            }
        }

        pollButton(selectAction_, selectArmed_, kInputSelect);
        pollButton(infoAction_, infoArmed_, kInputInfo);

        // Up and down move the highlight. Left and right already turn the view, so
        // the stick does two jobs and which one depends on the axis, not on a mode.
        if (scrollArmed_ && fabsf(y) > kTurnEngage) {
            notifyInput(y > 0.0f ? kInputUp : kInputDown);
            scrollArmed_ = false;
        } else if (!scrollArmed_ && fabsf(y) < kTurnRelease) {
            scrollArmed_ = true;
        }
    }

    /** One edge-triggered boolean action, reported once per press. */
    void pollButton(XrAction action, bool &armed, int code) {
        if (action == XR_NULL_HANDLE) return;
        XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
        gi.action = action;
        XrActionStateBoolean st{XR_TYPE_ACTION_STATE_BOOLEAN};
        if (!XR_SUCCEEDED(xrGetActionStateBoolean(session_, &gi, &st)) || !st.isActive) return;
        if (armed && st.currentState) {
            notifyInput(code);
            armed = false;
        } else if (!armed && !st.currentState) {
            armed = true;
        }
    }

    /**
     * Hand a button press to Kotlin and let it decide what it means.
     *
     * Deliberately dumb: native knows a button was pressed and nothing about
     * panoramas, selections or what is on screen. All of that lives on the Kotlin
     * side, which is where the file list and the text stack already are - the same
     * division that keeps the renderer free of drawing code.
     */
    void notifyInput(int code) {
        // Logged before it is delivered. "B does nothing" has two very different
        // causes - the binding never fired, or Kotlin ignored it - and without a
        // line here they look identical from the outside.
        LOGI("input %d", code);
        JNIEnv *env = nullptr;
        if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return;
        if (env == nullptr || g_activity == nullptr) return;
        jclass cls = env->GetObjectClass(g_activity);
        if (cls == nullptr) return;
        jmethodID m = env->GetMethodID(cls, "onVrInput", "(I)V");
        if (m != nullptr) env->CallVoidMethod(g_activity, m, code);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(cls);
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
                    // Same destination as the left menu button. On this device the
                    // aim bit reports valid with strength 0.00 and every joint at
                    // 0x0, so this has never actually fired - it is wired anyway so
                    // that a runtime which does deliver hand tracking gets the
                    // gesture for free, and the controller carries it meanwhile.
                    notifyInput(kInputMenu);
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
        swapFormat_ = chosen;

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

        // A cylindrical panorama gets an apron: hInset columns of the far edge
        // copied onto each side, so the texture wraps continuously instead of just
        // ending. The vertical trim worked because the rows it discarded were flat
        // gradient, and clamping to a flat colour is invisible. The wrap is real
        // image, so there is nothing safe to clamp to - the fix has to be giving the
        // filter correct pixels to reach into rather than taking pixels away.
        apron_ = pano.cube ? 0 : kApronColumns;
        // A panorama downscaled to exactly the swapchain limit has no room for one.
        // Losing the apron costs the seam fix on that file; failing to create the
        // swapchain costs the whole panorama, so the apron gives way.
        if (!pano.cube && maxSwapW_ > 0 &&
            static_cast<uint32_t>(pano.width + 2 * apron_) > maxSwapW_) {
            apron_ = static_cast<int>((maxSwapW_ - static_cast<uint32_t>(pano.width)) / 2);
            if (apron_ < 0) apron_ = 0;
            LOGI("apron trimmed to %d columns - %d + apron would pass the %u limit",
                 apron_, pano.width, maxSwapW_);
        }
        swWidth_ = pano.cube ? pano.faceSize : pano.width + 2 * apron_;
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

    /**
     * Slide the panorama horizontally, wrapping, before it is ever uploaded.
     *
     * Pure diagnosis. The image's own left/right join normally lands exactly on an
     * arc boundary, so a line there could be either one. Rolling by half an arc
     * separates them: whatever stays put is the layer edge, whatever moves is the
     * image. Costs one pass over the buffer and nothing at all when the property is
     * unset, which is every normal run.
     */
    void rollPanorama(int milliTurns) {
        const int w = swWidth_, h = swHeight_;
        long shift = (static_cast<long>(milliTurns) * w / 1000) % w;
        if (shift < 0) shift += w;
        if (shift == 0) return;
        // pano_ is const and shared; the roll gets its own buffer rather than
        // casting that away. Only allocated when the property is set.
        rolled_ = pano_->rgba;
        std::vector<uint8_t> row(static_cast<size_t>(w) * 4);
        for (int y = 0; y < h; ++y) {
            uint8_t *line = rolled_.data() + static_cast<size_t>(y) * w * 4;
            memcpy(row.data(), line, row.size());
            const size_t cut = static_cast<size_t>(shift) * 4;
            memcpy(line, row.data() + cut, row.size() - cut);
            memcpy(line + (row.size() - cut), row.data(), cut);
        }
        LOGI("panorama rolled %ld columns (%d/1000 of a turn) - diagnostic only",
             shift, milliTurns);
    }

    void uploadImage(uint32_t index) {
        if (pano_->cube) { uploadCube(index); return; }
        glBindTexture(GL_TEXTURE_2D, images_[index].image);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        const uint8_t *src = rolled_.empty() ? pano_->rgba.data() : rolled_.data();
        if (apron_ == 0) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, swWidth_, swHeight_, GL_RGBA,
                            GL_UNSIGNED_BYTE, src);
        } else {
            // [ last apron_ cols | the whole image | first apron_ cols ]
            const int imgW = pano_->width;
            const size_t srcStride = static_cast<size_t>(imgW) * 4;
            const size_t dstStride = static_cast<size_t>(swWidth_) * 4;
            const size_t apronBytes = static_cast<size_t>(apron_) * 4;
            padded_.assign(dstStride * static_cast<size_t>(swHeight_), 0);
            for (int y = 0; y < swHeight_; ++y) {
                const uint8_t *r = src + static_cast<size_t>(y) * srcStride;
                uint8_t *w = padded_.data() + static_cast<size_t>(y) * dstStride;
                memcpy(w, r + srcStride - apronBytes, apronBytes);          // wrap in
                memcpy(w + apronBytes, r, srcStride);                       // the image
                memcpy(w + apronBytes + srcStride, r, apronBytes);          // wrap out
            }
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, swWidth_, swHeight_, GL_RGBA,
                            GL_UNSIGNED_BYTE, padded_.data());
            padded_.clear();
            padded_.shrink_to_fit();
        }
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
        // How far inside the rim each polar cap sits, in thousandths. 1000 is exactly
        // at the rim, which is where the top and bottom hairlines come from; less
        // overlaps the cylinder and hides the join. Tunable without a rebuild because
        // finding the smallest value that closes it needs eyes in a headset.
        int capInsetMilli = 970;
        if (__system_property_get("debug.questtime.capinset", propBuf) > 0) {
            capInsetMilli = atoi(propBuf);
        }
        if (capInsetMilli < 1) capInsetMilli = 1;
        if (capInsetMilli > 1000) capInsetMilli = 1000;
        const float capInset = static_cast<float>(capInsetMilli) * 0.001f;

        // How many texture rows to leave unsampled at the top and bottom of every
        // arc. The horizontal bleed has a vertical twin: an arc's imageRect spans the
        // full height, so at the outermost row the compositor's filter kernel reaches
        // past the rect exactly as it does at the wrap, and that shows as a hairline
        // along the top and bottom of the cylinder. Those rows are deep inside the
        // flat gradient - the photograph stops around 44 degrees and this is at 60 -
        // so discarding several costs nothing visible and closes the join.
        // 8 rows. For a typical file the flat gradient runs to several hundred rows
        // at each end - Monument Valley pads 442 - so this is nowhere near the
        // photograph. It is not unlimited though: a panorama already taller than the
        // gradient's target gets no padding at all, and there the trimmed rows would
        // be real image. Raise it with the property if a file still shows the line.
        int vInset = 8;
        if (__system_property_get("debug.questtime.vinset", propBuf) > 0) {
            vInset = atoi(propBuf);
        }
        if (vInset < 0) vInset = 0;
        if (vInset > swHeight_ / 4) vInset = swHeight_ / 4;

        // Rotate the panorama's pixels before upload, so the image's own wrap stops
        // coinciding with an arc boundary. This is the experiment that tells us what
        // the line behind you actually is: if it stays put while the image slides
        // under it, it belongs to the layer edge; if it travels with the image, the
        // arcs are innocent. Thousandths of a turn.
        int rollMilli = 0;
        if (__system_property_get("debug.questtime.roll", propBuf) > 0) {
            rollMilli = atoi(propBuf);
        }

        if (rollMilli != 0 && !pano.cube) rollPanorama(rollMilli);

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
             "perArc=%.6f bleed=%.4fdeg vinset=%d capinset=%d roll=%d probe=%d",
             centralAngle, pano.centralAngle, pano.aspect, radius, arcs,
             centralAngle / arcs, bleedMilliDeg * 0.001, vInset, capInsetMilli,
             rollMilli, probe ? 1 : 0);
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
            layers.reserve(arcs + 4);   // two caps, the probe, the menu

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
                // The bar goes on last. Composition layers composite in submission
                // order, not by depth, so anything submitted after it would paint
                // over it however far away that layer claims to be.
                XrCompositionLayerQuad cubeMenu{};
                const XrCompositionLayerBaseHeader *cubeBar = menuLayer(cubeMenu);
                const XrCompositionLayerBaseHeader *cubeWithMenu[2] = {
                    cubeLayers[0], cubeBar};
                const uint32_t cubeCount = cubeBar ? 2u : 1u;

                XrFrameEndInfo cubeEnd{XR_TYPE_FRAME_END_INFO};
                cubeEnd.displayTime = frameState.predictedDisplayTime;
                cubeEnd.environmentBlendMode = blendMode_;
                cubeEnd.layerCount = frameState.shouldRender ? cubeCount : 0;
                cubeEnd.layers = frameState.shouldRender ? cubeWithMenu : nullptr;
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
            // The arcs now stop a few rows short, so the hole the caps have to fill
            // is correspondingly larger. Deriving halfV from the same trimmed
            // geometry keeps the two agreeing however vInset is set.
            const float vScaleCap = static_cast<float>(swHeight_) /
                static_cast<float>(swHeight_ - 2 * vInset);
            const float halfV = atanf(centralAngle / (2.0f * pano.aspect * vScaleCap));
            // Pull each cap *inside* the rim so it overlaps the cylinder rather than
            // meeting it edge to edge. Sitting exactly at the rim is geometrically
            // correct and visually wrong: two layers abutting with no overlap is the
            // same situation as the wrap behind you, and it shows as the same
            // hairline - which is why there are three lines, top, bottom and back,
            // not one. The caps are submitted before the arcs, so any overlap is
            // painted over and costs nothing but a sliver of flat colour.
            const float rimY = radius * tanf(halfV) * capInset;
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
                // Slices divide the *image*, not the padded texture. Each one then
                // reaches apron_ columns further out on both sides, so neighbouring
                // arcs overlap in texture space with correct content - including the
                // pair either side of the wrap, which is the whole point.
                const int32_t imgW = swWidth_ - 2 * apron_;
                const int32_t i0 = static_cast<int32_t>(
                    static_cast<int64_t>(i) * imgW / arcs);
                const int32_t i1 = static_cast<int32_t>(
                    static_cast<int64_t>(i + 1) * imgW / arcs);
                const int32_t x0 = apron_ + i0 - apron_;
                const int32_t x1 = apron_ + i1 + apron_;
                const int32_t sliceW = x1 - x0;

                // Where this slice's centre sits relative to straight ahead.
                // Positive is to the viewer's right.
                const float phi =
                    (static_cast<float>(i0 + i1) * 0.5f) / static_cast<float>(imgW) - 0.5f;
                const float toRight = phi * centralAngle;
                // A positive rotation about +Y swings -Z to the left, so negate.
                const float theta = -toRight + yaw_;
                // The angle an arc covers is set by how much of the *image* it
                // shows, apron included: the apron is real image too, it is just
                // image its neighbour also shows.
                const float sliceFrac =
                    static_cast<float>(sliceW) / static_cast<float>(imgW);

                XrCompositionLayerCylinderKHR &c = cyls[i];
                c = XrCompositionLayerCylinderKHR{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
                c.layerFlags = 0;
                c.space = space_;
                c.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
                c.subImage.swapchain = swapchain_;
                c.subImage.imageRect.offset = {x0, vInset};
                c.subImage.imageRect.extent = {sliceW, swHeight_ - 2 * vInset};
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
                // Fewer rows sampled means a wider effective aspect: the runtime
                // derives the vertical extent from centralAngle / (2 * aspectRatio),
                // so without this correction trimming rows would silently lower the
                // horizon instead of just hiding the edge.
                const float vScale = static_cast<float>(swHeight_) /
                    static_cast<float>(swHeight_ - 2 * vInset);
                c.aspectRatio = pano.aspect * sliceFrac * grow * vScale;
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

            // Last, and after the arcs rather than before them like the caps:
            // composition order is paint order, so the bar has to be submitted
            // after everything it is meant to sit in front of.
            XrCompositionLayerQuad menuQuad{};
            if (const XrCompositionLayerBaseHeader *bar = menuLayer(menuQuad)) {
                layers.push_back(bar);
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
        // A Viewer is built per panorama, so the menu's swapchain and space have to
        // go with it or every file opened leaks one of each.
        if (menuSwapchain_ != XR_NULL_HANDLE) xrDestroySwapchain(menuSwapchain_);
        if (menuSpace_ != XR_NULL_HANDLE) xrDestroySpace(menuSpace_);
        if (space_ != XR_NULL_HANDLE) xrDestroySpace(space_);
        if (session_ != XR_NULL_HANDLE) xrDestroySession(session_);
        if (instance_ != XR_NULL_HANDLE) xrDestroyInstance(instance_);
        leftHand_ = XR_NULL_HANDLE;
        swapchain_ = XR_NULL_HANDLE;
        menuSwapchain_ = XR_NULL_HANDLE;
        menuSpace_ = XR_NULL_HANDLE;
        menuImages_.clear();
        menuUploaded_ = false;
        space_ = XR_NULL_HANDLE;
        session_ = XR_NULL_HANDLE;
        instance_ = XR_NULL_HANDLE;
        pano_ = nullptr;
        rolled_.clear();
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

/**
 * Hand over the menu bar's pixels, already drawn.
 *
 * Kotlin draws it because Kotlin has a text stack: laying out a line of type in C++
 * here would mean shipping a font and a rasteriser to redo what android.graphics
 * already does. The result is RGBA, premultiplied the way Canvas leaves it.
 *
 * Safe to call before or after the viewer starts; the bar is picked up the first
 * time it is actually shown.
 */
JNIEXPORT void JNICALL
Java_com_questtime_vr_VrActivity_nativeShowMenu(JNIEnv *, jobject, jboolean show) {
    std::lock_guard<std::mutex> lock(g_menuMutex);
    g_menuWanted = show == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_questtime_vr_VrActivity_nativeSetPicking(JNIEnv *, jobject, jboolean picking) {
    std::lock_guard<std::mutex> lock(g_menuMutex);
    g_picking = picking == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_questtime_vr_VrActivity_nativeSetMenu(
    JNIEnv *env, jobject, jobject buffer, jint width, jint height) {
    auto *src = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    const jlong need = static_cast<jlong>(width) * height * 4;
    if (src == nullptr || width <= 0 || height <= 0 ||
        env->GetDirectBufferCapacity(buffer) < need) {
        LOGE("menu bitmap rejected: %dx%d", width, height);
        return;
    }
    std::lock_guard<std::mutex> lock(g_menuMutex);
    // Flipped on the way in, for the same reason the panorama is: row 0 of a GL
    // texture is the bottom row, and the compositor samples the subImage top-down.
    const size_t stride = static_cast<size_t>(width) * 4;
    g_menuPixels.assign(stride * static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        memcpy(g_menuPixels.data() + static_cast<size_t>(y) * stride,
               src + static_cast<size_t>(height - 1 - y) * stride, stride);
    }
    g_menuW = width;
    g_menuH = height;
    g_menuVersion++;
    LOGI("menu bitmap received: %dx%d (v%llu)", width, height,
         static_cast<unsigned long long>(g_menuVersion));
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
