#pragma once

/**
 * The first thing this app ever draws for itself.
 *
 * Everything else reaches the compositor as a composition layer whose projection
 * already matches how QuickTime VR stored the picture - that is the idea the whole
 * renderer rests on, and it is why there are no shaders anywhere else. Controllers
 * cannot work that way: they are geometry at a pose, and geometry needs a camera.
 *
 * So this is a projection layer, and it is deliberately the *only* one. It renders
 * into its own eye buffers, clears them to fully transparent, draws the controllers,
 * and hands the result over with
 * `XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT` so everything that is not a
 * controller lets the panorama through untouched. The cylinder layers keep sampling
 * the panorama at full display resolution exactly as before - the last attempt at an
 * eye-buffer renderer was reverted for costing that, and this one does not, because
 * the panorama never passes through it.
 *
 * Submitted last, after the panorama and before nothing: composition order is paint
 * order, so the controllers sit in front of the world and behind any panel.
 */

// The platform header only declares XrSwapchainImageOpenGLESKHR when these are set,
// and they must be set *before* it is included. vr_renderer.cpp does the same; saying
// it here as well means this header is correct whichever order they are included in.
#ifndef XR_USE_PLATFORM_ANDROID
#define XR_USE_PLATFORM_ANDROID
#endif
#ifndef XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_GRAPHICS_API_OPENGL_ES
#endif

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <cmath>
#include <cstring>
#include <vector>

namespace ctrl {

/** Column-major 4x4, the layout GL wants. */
struct Mat4 {
    float m[16];
};

inline Mat4 identity() {
    Mat4 r{};
    r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1.0f;
    return r;
}

/**
 * Projection from an [XrFovf].
 *
 * The four angles are **not symmetric** on this hardware - a Quest's lenses look
 * outwards, so left and right differ - and a textbook `perspective(fovy, aspect)`
 * silently throws that away. Building it from the four tangents is the only version
 * that is correct here, and the error it avoids is a subtle wrong parallax rather
 * than an obviously broken picture.
 */
inline Mat4 projection(const XrFovf &fov, float nearZ, float farZ) {
    const float l = tanf(fov.angleLeft);
    const float r = tanf(fov.angleRight);
    const float d = tanf(fov.angleDown);
    const float u = tanf(fov.angleUp);
    const float w = r - l;
    const float h = u - d;

    Mat4 p{};
    p.m[0] = 2.0f / w;
    p.m[5] = 2.0f / h;
    p.m[8] = (r + l) / w;
    p.m[9] = (u + d) / h;
    p.m[10] = -(farZ + nearZ) / (farZ - nearZ);
    p.m[11] = -1.0f;
    p.m[14] = -(2.0f * farZ * nearZ) / (farZ - nearZ);
    return p;
}

/** A pose as a model matrix: rotate, then translate. */
inline Mat4 fromPose(const XrPosef &pose, float sx = 1.0f, float sy = 1.0f, float sz = 1.0f) {
    const XrQuaternionf &q = pose.orientation;
    const float x = q.x, y = q.y, z = q.z, w = q.w;
    Mat4 r{};
    r.m[0] = (1 - 2 * (y * y + z * z)) * sx;
    r.m[1] = (2 * (x * y + z * w)) * sx;
    r.m[2] = (2 * (x * z - y * w)) * sx;
    r.m[4] = (2 * (x * y - z * w)) * sy;
    r.m[5] = (1 - 2 * (x * x + z * z)) * sy;
    r.m[6] = (2 * (y * z + x * w)) * sy;
    r.m[8] = (2 * (x * z + y * w)) * sz;
    r.m[9] = (2 * (y * z - x * w)) * sz;
    r.m[10] = (1 - 2 * (x * x + y * y)) * sz;
    r.m[12] = pose.position.x;
    r.m[13] = pose.position.y;
    r.m[14] = pose.position.z;
    r.m[15] = 1.0f;
    return r;
}

/**
 * The *inverse* of a pose, which is what a view matrix is.
 *
 * Built by transposing the rotation and negating the rotated translation rather than
 * by a general inverse: a pose is a rotation and a translation, so the general case
 * cannot arise and the cheap form cannot be wrong for a reason a general one would
 * catch.
 */
inline Mat4 viewFromPose(const XrPosef &pose) {
    const Mat4 m = fromPose(pose);
    Mat4 v = identity();
    // transpose the 3x3
    v.m[0] = m.m[0];  v.m[1] = m.m[4];  v.m[2] = m.m[8];
    v.m[4] = m.m[1];  v.m[5] = m.m[5];  v.m[6] = m.m[9];
    v.m[8] = m.m[2];  v.m[9] = m.m[6];  v.m[10] = m.m[10];
    const float px = pose.position.x, py = pose.position.y, pz = pose.position.z;
    v.m[12] = -(v.m[0] * px + v.m[4] * py + v.m[8] * pz);
    v.m[13] = -(v.m[1] * px + v.m[5] * py + v.m[9] * pz);
    v.m[14] = -(v.m[2] * px + v.m[6] * py + v.m[10] * pz);
    return v;
}

inline Mat4 multiply(const Mat4 &a, const Mat4 &b) {
    Mat4 r{};
    for (int c = 0; c < 4; ++c) {
        for (int i = 0; i < 4; ++i) {
            r.m[c * 4 + i] = a.m[0 * 4 + i] * b.m[c * 4 + 0] +
                             a.m[1 * 4 + i] * b.m[c * 4 + 1] +
                             a.m[2 * 4 + i] * b.m[c * 4 + 2] +
                             a.m[3 * 4 + i] * b.m[c * 4 + 3];
        }
    }
    return r;
}

/** Position, then a normal, so the shader can shade rather than flat-fill. */
struct Vertex {
    float px, py, pz;
    float nx, ny, nz;
};

/** A box centred on x and y, running from 0 to -length in z (forward is -Z). */
inline void appendBox(std::vector<Vertex> &out, float halfW, float halfH, float length) {
    const float z0 = 0.0f, z1 = -length;
    struct Face { float n[3]; float v[4][3]; };
    const Face faces[6] = {
        {{0, 0, 1},  {{-halfW, -halfH, z0}, {halfW, -halfH, z0}, {halfW, halfH, z0}, {-halfW, halfH, z0}}},
        {{0, 0, -1}, {{halfW, -halfH, z1}, {-halfW, -halfH, z1}, {-halfW, halfH, z1}, {halfW, halfH, z1}}},
        {{-1, 0, 0}, {{-halfW, -halfH, z1}, {-halfW, -halfH, z0}, {-halfW, halfH, z0}, {-halfW, halfH, z1}}},
        {{1, 0, 0},  {{halfW, -halfH, z0}, {halfW, -halfH, z1}, {halfW, halfH, z1}, {halfW, halfH, z0}}},
        {{0, 1, 0},  {{-halfW, halfH, z0}, {halfW, halfH, z0}, {halfW, halfH, z1}, {-halfW, halfH, z1}}},
        {{0, -1, 0}, {{-halfW, -halfH, z1}, {halfW, -halfH, z1}, {halfW, -halfH, z0}, {-halfW, -halfH, z0}}},
    };
    for (const Face &f : faces) {
        const int order[6] = {0, 1, 2, 0, 2, 3};
        for (int i : order) {
            out.push_back({f.v[i][0], f.v[i][1], f.v[i][2], f.n[0], f.n[1], f.n[2]});
        }
    }
}

}  // namespace ctrl

/**
 * Eye buffers, a shader, and a controller-shaped lump of geometry.
 *
 * Kept out of vr_renderer.cpp because it is a different kind of thing: that file
 * describes panoramas to a compositor, and this one is a small conventional 3D
 * renderer. Mixing them would make it look as though the panorama path had grown a
 * camera, which is exactly what has always been avoided.
 */
class ControllerRenderer {
public:
    bool init(XrInstance instance, XrSystemId system, XrSession session,
              void (*logi)(const char *, ...), void (*loge)(const char *, ...)) {
        logi_ = logi;
        loge_ = loge;
        session_ = session;

        uint32_t count = 0;
        if (XR_FAILED(xrEnumerateViewConfigurationViews(
                instance, system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count, nullptr)) ||
            count != 2) {
            return fail("this needs exactly two views");
        }
        std::vector<XrViewConfigurationView> cfg(
            count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
        if (XR_FAILED(xrEnumerateViewConfigurationViews(
                instance, system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                count, &count, cfg.data()))) {
            return fail("could not read the view configuration");
        }

        // Deliberately the *recommended* size rather than the maximum. Nothing here
        // benefits from more pixels - it is a controller, not a photograph - and the
        // panorama is what the GPU budget belongs to.
        width_ = static_cast<int>(cfg[0].recommendedImageRectWidth);
        height_ = static_cast<int>(cfg[0].recommendedImageRectHeight);

        for (int eye = 0; eye < 2; ++eye) {
            XrSwapchainCreateInfo ci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            ci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                            XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
            ci.format = GL_RGBA8;
            ci.sampleCount = 1;
            ci.width = width_;
            ci.height = height_;
            ci.faceCount = 1;
            ci.arraySize = 1;
            ci.mipCount = 1;
            if (XR_FAILED(xrCreateSwapchain(session, &ci, &swap_[eye]))) {
                return fail("could not create an eye swapchain");
            }
            uint32_t n = 0;
            xrEnumerateSwapchainImages(swap_[eye], 0, &n, nullptr);
            images_[eye].assign(n, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            xrEnumerateSwapchainImages(
                swap_[eye], n, &n,
                reinterpret_cast<XrSwapchainImageBaseHeader *>(images_[eye].data()));
        }

        if (!buildProgram()) return false;
        buildMesh();

        glGenFramebuffers(1, &fbo_);
        glGenRenderbuffers(1, &depth_);
        glBindRenderbuffer(GL_RENDERBUFFER, depth_);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width_, height_);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        ready_ = true;
        logi_("controllers: eye buffers %dx%d, %zu images each",
              width_, height_, images_[0].size());
        return true;
    }

    bool ready() const { return ready_; }

    /**
     * Draw both controllers and fill in a projection layer.
     *
     * Returns false when there is nothing to show - no views, no tracked controller -
     * and in that case the layer must not be submitted. Submitting an empty
     * projection layer would be a full-screen transparent quad per frame for nothing.
     */
    bool render(XrTime time, XrSpace baseSpace,
                const XrPosef grip[2], const bool gripValid[2],
                const XrPosef aim[2], const bool aimValid[2],
                XrCompositionLayerProjection &layer,
                XrCompositionLayerProjectionView views[2]) {
        if (!ready_) return false;
        if (!gripValid[0] && !gripValid[1] && !aimValid[0] && !aimValid[1]) return false;

        XrViewState vs{XR_TYPE_VIEW_STATE};
        uint32_t found = 0;
        XrView xrViews[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
        XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
        li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        li.displayTime = time;
        li.space = baseSpace;
        if (XR_FAILED(xrLocateViews(session_, &li, &vs, 2, &found, xrViews)) || found != 2) {
            return false;
        }
        const XrViewStateFlags need =
            XR_VIEW_STATE_POSITION_VALID_BIT | XR_VIEW_STATE_ORIENTATION_VALID_BIT;
        if ((vs.viewStateFlags & need) != need) return false;

        for (int eye = 0; eye < 2; ++eye) {
            uint32_t index = 0;
            XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
            if (XR_FAILED(xrAcquireSwapchainImage(swap_[eye], &ai, &index))) return false;
            XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
            wi.timeout = XR_INFINITE_DURATION;
            xrWaitSwapchainImage(swap_[eye], &wi);

            drawEye(xrViews[eye], images_[eye][index].image, grip, gripValid, aim, aimValid);

            XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(swap_[eye], &ri);

            views[eye] = XrCompositionLayerProjectionView{
                XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
            views[eye].pose = xrViews[eye].pose;
            views[eye].fov = xrViews[eye].fov;
            views[eye].subImage.swapchain = swap_[eye];
            views[eye].subImage.imageRect.offset = {0, 0};
            views[eye].subImage.imageRect.extent = {width_, height_};
            views[eye].subImage.imageArrayIndex = 0;
        }

        layer = XrCompositionLayerProjection{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
        // Everything that is not a controller was cleared to alpha 0, so this flag is
        // what lets the panorama through. Without it the layer is an opaque black
        // wall with two controllers painted on it.
        layer.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT |
                           XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT;
        layer.space = baseSpace;
        layer.viewCount = 2;
        layer.views = views;
        return true;
    }

    void destroy() {
        if (fbo_) { glDeleteFramebuffers(1, &fbo_); fbo_ = 0; }
        if (depth_) { glDeleteRenderbuffers(1, &depth_); depth_ = 0; }
        if (vbo_) { glDeleteBuffers(1, &vbo_); vbo_ = 0; }
        if (program_) { glDeleteProgram(program_); program_ = 0; }
        for (int eye = 0; eye < 2; ++eye) {
            if (swap_[eye] != XR_NULL_HANDLE) xrDestroySwapchain(swap_[eye]);
            swap_[eye] = XR_NULL_HANDLE;
            images_[eye].clear();
        }
        ready_ = false;
    }

private:
    bool fail(const char *why) {
        if (loge_) loge_("controllers: %s", why);
        return false;
    }

    void drawEye(const XrView &view, GLuint colour,
                 const XrPosef grip[2], const bool gripValid[2],
                 const XrPosef aim[2], const bool aimValid[2]) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo_);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colour, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth_);

        glViewport(0, 0, width_, height_);
        glDisable(GL_SCISSOR_TEST);
        // Transparent, not black. This clear is the whole reason the panorama still
        // shows through everywhere a controller is not.
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClearDepthf(1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        const ctrl::Mat4 proj = ctrl::projection(view.fov, 0.02f, 50.0f);
        const ctrl::Mat4 v = ctrl::viewFromPose(view.pose);
        const ctrl::Mat4 viewProj = ctrl::multiply(proj, v);

        glUseProgram(program_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(ctrl::Vertex), (void *)0);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(ctrl::Vertex),
                              (void *)(sizeof(float) * 3));

        for (int hand = 0; hand < 2; ++hand) {
            if (gripValid[hand]) {
                const ctrl::Mat4 model = ctrl::fromPose(grip[hand]);
                drawPart(viewProj, model, bodyFirst_, bodyCount_, 0.62f, 0.64f, 0.70f, 1.0f);
            }
            if (aimValid[hand]) {
                // The beam is drawn from the aim pose, which is the ray the runtime
                // says the controller points along - the same pose the menu uses, so
                // what is drawn and what is pointed at cannot disagree.
                const ctrl::Mat4 model = ctrl::fromPose(aim[hand]);
                drawPart(viewProj, model, beamFirst_, beamCount_, 0.30f, 0.62f, 0.90f, 0.85f);
            }
        }

        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void drawPart(const ctrl::Mat4 &viewProj, const ctrl::Mat4 &model,
                  int first, int count, float r, float g, float b, float a) {
        const ctrl::Mat4 mvp = ctrl::multiply(viewProj, model);
        glUniformMatrix4fv(uMvp_, 1, GL_FALSE, mvp.m);
        glUniformMatrix4fv(uModel_, 1, GL_FALSE, model.m);
        glUniform4f(uColour_, r, g, b, a);
        glDrawArrays(GL_TRIANGLES, first, count);
    }

    bool buildProgram() {
        static const char *vs =
            "#version 300 es\n"
            "layout(location=0) in vec3 aPos;\n"
            "layout(location=1) in vec3 aNormal;\n"
            "uniform mat4 uMvp;\n"
            "uniform mat4 uModel;\n"
            "out vec3 vNormal;\n"
            "void main() {\n"
            "  vNormal = mat3(uModel) * aNormal;\n"
            "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
            "}\n";
        // One fixed light from above and in front. A controller lit from nowhere is a
        // flat silhouette and reads as a sticker rather than an object in the room.
        static const char *fs =
            "#version 300 es\n"
            "precision mediump float;\n"
            "in vec3 vNormal;\n"
            "uniform vec4 uColour;\n"
            "out vec4 fragColour;\n"
            "void main() {\n"
            "  vec3 n = normalize(vNormal);\n"
            "  float lit = 0.45 + 0.55 * max(dot(n, normalize(vec3(0.3, 0.8, 0.5))), 0.0);\n"
            "  fragColour = vec4(uColour.rgb * lit, uColour.a);\n"
            "}\n";

        GLuint v = compile(GL_VERTEX_SHADER, vs);
        GLuint f = compile(GL_FRAGMENT_SHADER, fs);
        if (!v || !f) return fail("a shader would not compile");
        program_ = glCreateProgram();
        glAttachShader(program_, v);
        glAttachShader(program_, f);
        glLinkProgram(program_);
        GLint ok = 0;
        glGetProgramiv(program_, GL_LINK_STATUS, &ok);
        glDeleteShader(v);
        glDeleteShader(f);
        if (!ok) {
            char log[512] = {0};
            glGetProgramInfoLog(program_, sizeof(log), nullptr, log);
            loge_("controllers: link failed: %s", log);
            return false;
        }
        uMvp_ = glGetUniformLocation(program_, "uMvp");
        uModel_ = glGetUniformLocation(program_, "uModel");
        uColour_ = glGetUniformLocation(program_, "uColour");
        return true;
    }

    GLuint compile(GLenum type, const char *src) {
        GLuint s = glCreateShader(type);
        glShaderSource(s, 1, &src, nullptr);
        glCompileShader(s);
        GLint ok = 0;
        glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
        if (!ok) {
            char log[512] = {0};
            glGetShaderInfoLog(s, sizeof(log), nullptr, log);
            if (loge_) loge_("controllers: shader: %s", log);
            glDeleteShader(s);
            return 0;
        }
        return s;
    }

    /**
     * A controller proxy and a beam, in one buffer.
     *
     * Not a model of a Touch controller. Meta ships the real meshes through
     * XR_FB_render_model, and loading one is worth doing once this pipeline is known
     * to work - but a recognisable lump at the right pose answers "where is my hand"
     * today, and a wrong mesh in the right place would look the same as a right mesh
     * in the wrong place, which is the confusion worth avoiding first.
     */
    void buildMesh() {
        std::vector<ctrl::Vertex> verts;
        bodyFirst_ = 0;
        ctrl::appendBox(verts, 0.022f, 0.016f, 0.09f);   // grip: roughly a hand's width
        bodyCount_ = static_cast<int>(verts.size());
        beamFirst_ = bodyCount_;
        ctrl::appendBox(verts, 0.004f, 0.004f, 0.60f);   // the ray, thin and long
        beamCount_ = static_cast<int>(verts.size()) - beamFirst_;

        glGenBuffers(1, &vbo_);
        glBindBuffer(GL_ARRAY_BUFFER, vbo_);
        glBufferData(GL_ARRAY_BUFFER,
                     static_cast<GLsizeiptr>(verts.size() * sizeof(ctrl::Vertex)),
                     verts.data(), GL_STATIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    XrSession session_ = XR_NULL_HANDLE;
    XrSwapchain swap_[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    std::vector<XrSwapchainImageOpenGLESKHR> images_[2];
    int width_ = 0, height_ = 0;
    GLuint fbo_ = 0, depth_ = 0, vbo_ = 0, program_ = 0;
    GLint uMvp_ = -1, uModel_ = -1, uColour_ = -1;
    int bodyFirst_ = 0, bodyCount_ = 0, beamFirst_ = 0, beamCount_ = 0;
    bool ready_ = false;
    void (*logi_)(const char *, ...) = nullptr;
    void (*loge_)(const char *, ...) = nullptr;
};
