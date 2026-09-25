/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Minimal x264 wrapper for the ntsc-rs Android port.
 *
 * Mirrors the desktop app's GStreamer x264enc setup exactly:
 *   - single-pass constant-quantizer mode ("pass=quant"):
 *       i_qp_constant = 50 - quality   (PC: quantizer = 50 - quality)
 *   - speed preset mirrored from PC (PC: GstX264EncPreset value 9 - speed):
 *       speed 0..8 -> veryslow..ultrafast
 *   - 8-bit, profile high (4:2:0) or high444 (4:4:4, i.e. no subsampling)
 *
 * NOTE: libx264 is GPL-2.0. This port links it for private use only.
 * See rust/x264/COPYING. If this app is ever distributed, the complete
 * corresponding source (including x264) must be offered per the GPL.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <x264.h>

typedef struct {
    x264_t *enc;
    int w;
    int h;
    int csp;
} enc_handle_t;

static const char *const PRESETS[9] = {
    "veryslow", "slower", "slow", "medium",
    "fast", "faster", "veryfast", "superfast", "ultrafast"
};

void *x264enc_open(int width, int height, int fps_num, int fps_den,
                   int quality_5_50, int speed_0_8, int use_444,
                   int interlaced, int tff) {
    if (width <= 0 || height <= 0) return 0;
    if (quality_5_50 < 0) quality_5_50 = 0;
    if (quality_5_50 > 50) quality_5_50 = 50;
    if (speed_0_8 < 0) speed_0_8 = 0;
    if (speed_0_8 > 8) speed_0_8 = 8;

    x264_param_t p;
    if (x264_param_default_preset(&p, PRESETS[speed_0_8], 0) != 0) return 0;

    p.i_width = width;
    p.i_height = height;
    p.i_fps_num = fps_num > 0 ? fps_num : 30;
    p.i_fps_den = fps_den > 0 ? fps_den : 1;
    /* Timebase = 1/fps so input pts can simply be the frame index. */
    p.i_timebase_num = p.i_fps_den;
    p.i_timebase_den = p.i_fps_num;
    p.b_vfr_input = 0;

    p.i_csp = use_444 ? X264_CSP_I444 : X264_CSP_I420;

    /* Constant-quantizer mode, like desktop pass="quant". */
    p.rc.i_rc_method = X264_RC_CQP;
    p.rc.i_qp_constant = 50 - quality_5_50;

    if (x264_param_apply_profile(&p, use_444 ? "high444" : "high") != 0) return 0;

    p.b_interlaced = interlaced ? 1 : 0;
    p.b_tff = tff ? 1 : 0;
    /* Repeat SPS/PPS before every keyframe (in-band), exactly like the
     * desktop GStreamer x264enc pipeline. This makes the stream self-
     * describing even if a muxer ever drops the avcC codec data. */
    p.b_repeat_headers = 1;
    p.b_annexb = 1;

    x264_t *enc = x264_encoder_open(&p);
    if (!enc) return 0;

    enc_handle_t *h = (enc_handle_t *)calloc(1, sizeof(enc_handle_t));
    if (!h) {
        x264_encoder_close(enc);
        return 0;
    }
    h->enc = enc;
    h->w = width;
    h->h = height;
    h->csp = p.i_csp;
    return h;
}

/* Annex-B SPS/PPS headers for the muxer's codec-specific data. */
int x264enc_headers(void *handle, uint8_t *out, int cap, int *len) {
    enc_handle_t *h = (enc_handle_t *)handle;
    x264_nal_t *nal = 0;
    int nnal = 0;
    if (x264_encoder_headers(h->enc, &nal, &nnal) < 0) return -1;
    int total = 0;
    for (int i = 0; i < nnal; i++) {
        if (total + nal[i].i_payload > cap) return -2;
        memcpy(out + total, nal[i].p_payload, nal[i].i_payload);
        total += nal[i].i_payload;
    }
    *len = total;
    return 0;
}

/*
 * Encode one frame (yuv = packed I420 or I444 planes, matching open()).
 * Pass yuv == NULL to flush delayed frames.
 * Returns: >0 bytes of annex-B output written (single frame in DTS order),
 *          0 if no output yet, <0 on error (-2 = output buffer too small).
 */
int x264enc_encode(void *handle, const uint8_t *yuv, int64_t pts,
                   uint8_t *out, int cap, int64_t *out_pts, int64_t *out_dts) {
    enc_handle_t *h = (enc_handle_t *)handle;
    x264_picture_t *pin = 0;
    x264_picture_t pic;
    if (yuv) {
        memset(&pic, 0, sizeof(pic));
        int w = h->w, hd = h->h;
        pic.img.i_csp = h->csp;
        if (h->csp == X264_CSP_I444) {
            pic.img.i_stride[0] = w;
            pic.img.i_stride[1] = w;
            pic.img.i_stride[2] = w;
            pic.img.plane[0] = (uint8_t *)yuv;
            pic.img.plane[1] = (uint8_t *)yuv + w * hd;
            pic.img.plane[2] = (uint8_t *)yuv + w * hd * 2;
        } else {
            pic.img.i_stride[0] = w;
            pic.img.i_stride[1] = w / 2;
            pic.img.i_stride[2] = w / 2;
            pic.img.plane[0] = (uint8_t *)yuv;
            pic.img.plane[1] = (uint8_t *)yuv + w * hd;
            pic.img.plane[2] = (uint8_t *)yuv + w * hd + w * hd / 4;
        }
        pic.i_pts = pts;
        pic.i_type = X264_TYPE_AUTO;
        pin = &pic;
    }
    x264_nal_t *nal = 0;
    int nnal = 0;
    x264_picture_t pic_out;
    int sz = x264_encoder_encode(h->enc, &nal, &nnal, pin, &pic_out);
    if (sz < 0) return -1;
    if (sz == 0 || nnal == 0) {
        *out_pts = 0;
        *out_dts = 0;
        return 0;
    }
    if (sz > cap) return -2;
    int off = 0;
    for (int i = 0; i < nnal; i++) {
        memcpy(out + off, nal[i].p_payload, nal[i].i_payload);
        off += nal[i].i_payload;
    }
    *out_pts = pic_out.i_pts;
    *out_dts = pic_out.i_dts;
    return off;
}

int x264enc_delayed(void *handle) {
    return x264_encoder_delayed_frames(((enc_handle_t *)handle)->enc);
}

void x264enc_close(void *handle) {
    enc_handle_t *h = (enc_handle_t *)handle;
    x264_encoder_close(h->enc);
    free(h);
}
