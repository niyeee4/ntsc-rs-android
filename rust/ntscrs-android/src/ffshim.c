/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Thin helpers over libav* so Rust never depends on struct layouts.
 * Everything here is compiled for host + 4 Android ABIs.
 * License note: this file links libx264 (GPL-2.0) via libavcodec.
 * Private-use build; see rust/x264/COPYING and rust/ffmpeg/COPYING.GPLv2.
 */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
#include <libavutil/display.h>
#include <libswscale/swscale.h>

/* ---------------- packets ---------------- */
AVPacket *ff_pkt_alloc(void) { return av_packet_alloc(); }
void ff_pkt_free(AVPacket *p) { AVPacket *q = p; av_packet_free(&q); }
void ff_pkt_unref(AVPacket *p) { av_packet_unref(p); }
int64_t ff_pkt_pts(const AVPacket *p) { return p->pts; }
int ff_pkt_stream(const AVPacket *p) { return p->stream_index; }
int ff_pkt_flags(const AVPacket *p) { return p->flags; }
int ff_pkt_size(const AVPacket *p) { return p->size; }
void ff_pkt_set_stream(AVPacket *p, int s) { p->stream_index = s; }
void ff_pkt_shift_ts(AVPacket *p, int64_t off) {
    if (p->pts != AV_NOPTS_VALUE) p->pts -= off;
    if (p->dts != AV_NOPTS_VALUE) {
        p->dts -= off;
        if (p->dts < 0) p->dts = 0;
    }
}
void ff_pkt_rescale(AVPacket *p, int sn, int sd, int dn, int dd) {
    AVRational s = {sn, sd}, d = {dn, dd};
    av_packet_rescale_ts(p, s, d);
}
int64_t ff_rescale(int64_t a, int sn, int sd, int dn, int dd) {
    AVRational s = {sn, sd}, d = {dn, dd};
    return av_rescale_q(a, s, d);
}

/* ---------------- frames ---------------- */
AVFrame *ff_frame_alloc(void) { return av_frame_alloc(); }
void ff_frame_free(AVFrame *f) { AVFrame *p = f; av_frame_free(&p); }
void ff_frame_unref(AVFrame *f) { av_frame_unref(f); }
void ff_frame_info(const AVFrame *f, int *w, int *h, int *fmt, int64_t *pts) {
    if (w) *w = f->width;
    if (h) *h = f->height;
    if (fmt) *fmt = f->format;
    if (pts) *pts = f->pts;
}
int64_t ff_frame_bets(const AVFrame *f) { return f->best_effort_timestamp; }
uint8_t *ff_frame_plane(AVFrame *f, int p) { return f->data[p]; }
int ff_frame_stride(const AVFrame *f, int p) { return f->linesize[p]; }

/* Allocate a refcounted image frame (safe to hand to the encoder). */
AVFrame *ff_frame_alloc_image(int w, int h, int fmt) {
    AVFrame *f = av_frame_alloc();
    if (!f) return 0;
    f->width = w;
    f->height = h;
    f->format = fmt;
    if (av_frame_get_buffer(f, 32) < 0) {
        av_frame_free(&f);
        return 0;
    }
    return f;
}
void ff_frame_set_pts(AVFrame *f, int64_t pts) { f->pts = pts; }

/* ---------------- demux ---------------- */
typedef struct {
    AVFormatContext *ic;
    int vi, ai;
    AVCodecContext *vdec;
    AVRational vtb;
    int vtn, vtd; /* video stream time base */
    int atn, atd; /* audio stream time base (0 if none) */
    int fps_n, fps_d;
    int width, height;
    int64_t dur_us;
    int rotation;
} ff_demux;

static void set_err(char *err, int errlen, const char *what, int code) {
    if (!err || errlen <= 0) return;
    if (code) {
        char buf[256];
        av_strerror(code, buf, sizeof(buf));
        snprintf(err, errlen, "%s: %s", what, buf);
    } else {
        snprintf(err, errlen, "%s", what);
    }
}

ff_demux *ff_demux_open(const char *path, char *err, int errlen) {
    ff_demux *h = (ff_demux *)calloc(1, sizeof(ff_demux));
    if (!h) return 0;
    h->vi = h->ai = -1;
    int rc = avformat_open_input(&h->ic, path, 0, 0);
    if (rc < 0) { set_err(err, errlen, "open input", rc); free(h); return 0; }
    rc = avformat_find_stream_info(h->ic, 0);
    if (rc < 0) { set_err(err, errlen, "find stream info", rc); avformat_close_input(&h->ic); free(h); return 0; }
    h->vi = av_find_best_stream(h->ic, AVMEDIA_TYPE_VIDEO, -1, -1, 0, 0);
    if (h->vi < 0) { set_err(err, errlen, "no video stream", 0); avformat_close_input(&h->ic); free(h); return 0; }
    h->ai = av_find_best_stream(h->ic, AVMEDIA_TYPE_AUDIO, -1, -1, 0, 0);
    AVStream *vs = h->ic->streams[h->vi];
    AVCodecParameters *par = vs->codecpar;
    const AVCodec *dec = avcodec_find_decoder(par->codec_id);
    if (!dec) { set_err(err, errlen, "no decoder for codec", 0); avformat_close_input(&h->ic); free(h); return 0; }
    h->vdec = avcodec_alloc_context3(dec);
    if (!h->vdec) { set_err(err, errlen, "alloc decoder ctx", 0); avformat_close_input(&h->ic); free(h); return 0; }
    rc = avcodec_parameters_to_context(h->vdec, par);
    if (rc < 0) { set_err(err, errlen, "decoder params", rc); goto fail; }
    /* Software decode: never request hwaccel -> identical on every device. */
    rc = avcodec_open2(h->vdec, dec, 0);
    if (rc < 0) { set_err(err, errlen, "open decoder", rc); goto fail; }
    h->width = h->vdec->width;
    h->height = h->vdec->height;
    h->vtb = vs->time_base;
    h->vtn = vs->time_base.num; h->vtd = vs->time_base.den;
    AVRational fps = vs->avg_frame_rate.num ? vs->avg_frame_rate : vs->r_frame_rate;
    if (!fps.num || !fps.den) { fps.num = 30; fps.den = 1; }
    h->fps_n = fps.num; h->fps_d = fps.den;
    h->dur_us = (h->ic->duration == AV_NOPTS_VALUE) ? -1 : h->ic->duration;
    if (h->ai >= 0) {
        AVStream *as = h->ic->streams[h->ai];
        h->atn = as->time_base.num; h->atd = as->time_base.den;
    }
    {
        uint8_t *sd = av_stream_get_side_data(vs, AV_PKT_DATA_DISPLAYMATRIX, 0);
        h->rotation = sd ? (int)(av_display_rotation_get((int32_t *)sd) + 0.5) : 0;
        if (h->rotation < 0) h->rotation += 360;
    }
    return h;
fail:
    avcodec_free_context(&h->vdec);
    avformat_close_input(&h->ic);
    free(h);
    return 0;
}

void ff_demux_close(ff_demux *h) {
    if (!h) return;
    avcodec_free_context(&h->vdec);
    avformat_close_input(&h->ic);
    free(h);
}

void ff_demux_info(const ff_demux *h, int *w, int *hgt, int *fps_n, int *fps_d,
                   int64_t *dur_us, int *has_audio, int *rot,
                   int *vtn, int *vtd, int *atn, int *atd) {
    if (w) *w = h->width;
    if (hgt) *hgt = h->height;
    if (fps_n) *fps_n = h->fps_n;
    if (fps_d) *fps_d = h->fps_d;
    if (dur_us) *dur_us = h->dur_us;
    if (has_audio) *has_audio = h->ai >= 0;
    if (rot) *rot = h->rotation;
    if (vtn) *vtn = h->vtn;
    if (vtd) *vtd = h->vtd;
    if (atn) *atn = h->atn;
    if (atd) *atd = h->atd;
}

int ff_demux_vstream(const ff_demux *h) { return h->vi; }
int ff_demux_astream(const ff_demux *h) { return h->ai; }
AVCodecParameters *ff_demux_apar(ff_demux *h) {
    if (h->ai < 0) return 0;
    return h->ic->streams[h->ai]->codecpar;
}

int ff_demux_seek(ff_demux *h, int64_t ts_us) {
    int rc = av_seek_frame(h->ic, -1, ts_us, AVSEEK_FLAG_BACKWARD);
    if (rc >= 0) avcodec_flush_buffers(h->vdec);
    return rc;
}

int ff_demux_read(ff_demux *h, AVPacket *pkt) { return av_read_frame(h->ic, pkt); }
int ff_vdec_send(ff_demux *h, AVPacket *pkt) { return avcodec_send_packet(h->vdec, pkt); }
int ff_vdec_recv(ff_demux *h, AVFrame *f) { return avcodec_receive_frame(h->vdec, f); }

/* ---------------- swscale ---------------- */
struct SwsContext *ff_sws_open(int sw, int sh, int sfmt, int dw, int dh, int dfmt) {
    return sws_getContext(sw, sh, sfmt, dw, dh, dfmt, SWS_BILINEAR, 0, 0, 0);
}
int ff_sws_run(struct SwsContext *c, AVFrame *src, uint8_t *dst, int dst_stride) {
    uint8_t *d[4] = {dst, 0, 0, 0};
    int s[4] = {dst_stride, 0, 0, 0};
    return sws_scale(c, (const uint8_t *const *)src->data, src->linesize, 0, src->height, d, s);
}
void ff_sws_close(struct SwsContext *c) { sws_freeContext(c); }

/* ---------------- libx264 encoder via libavcodec ---------------- */
static const char *const PRESETS[9] = {
    "veryslow", "slower", "slow", "medium",
    "fast", "faster", "veryfast", "superfast", "ultrafast"
};

AVCodecContext *ff_enc_open(int w, int h, int fps_n, int fps_d,
                            int quality_0_48, int speed_0_8, int use444,
                            int interlaced, int tff, char *err, int errlen) {
    if (quality_0_48 < 0) quality_0_48 = 0;
    if (quality_0_48 > 48) quality_0_48 = 48;
    if (speed_0_8 < 0) speed_0_8 = 0;
    if (speed_0_8 > 8) speed_0_8 = 8;
    const AVCodec *enc = avcodec_find_encoder_by_name("libx264");
    if (!enc) { set_err(err, errlen, "libx264 encoder not found", 0); return 0; }
    AVCodecContext *c = avcodec_alloc_context3(enc);
    if (!c) { set_err(err, errlen, "alloc encoder ctx", 0); return 0; }
    c->width = w; c->height = h;
    c->pix_fmt = use444 ? AV_PIX_FMT_YUV444P : AV_PIX_FMT_YUV420P;
    c->time_base.num = fps_d; c->time_base.den = fps_n;
    c->framerate.num = fps_n; c->framerate.den = fps_d;
    c->gop_size = 250;
    c->max_b_frames = 3;
    c->thread_count = 0;
    c->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    AVDictionary *opts = 0;
    av_dict_set(&opts, "preset", PRESETS[speed_0_8], 0);
    {
        char qp[16];
        snprintf(qp, sizeof(qp), "%d", 50 - quality_0_48);
        av_dict_set(&opts, "qp", qp, 0); /* constant QP, like desktop pass=quant */
    }
    av_dict_set(&opts, "profile", use444 ? "high444" : "high", 0);
    if (interlaced) {
        char xp[64];
        snprintf(xp, sizeof(xp), "interlaced=1:tff=%d", tff ? 1 : 0);
        av_dict_set(&opts, "x264-params", xp, 0);
    }
    int rc = avcodec_open2(c, enc, &opts);
    av_dict_free(&opts);
    if (rc < 0) { set_err(err, errlen, "open libx264", rc); avcodec_free_context(&c); return 0; }
    return c;
}
void ff_enc_tb(const AVCodecContext *c, int *n, int *d) {
    if (n) *n = c->time_base.num;
    if (d) *d = c->time_base.den;
}
int ff_enc_send(AVCodecContext *c, AVFrame *f) { return avcodec_send_frame(c, f); }
int ff_enc_recv(AVCodecContext *c, AVPacket *p) { return avcodec_receive_packet(c, p); }
void ff_enc_close(AVCodecContext *c) { AVCodecContext *p = c; avcodec_free_context(&p); }

/* ---------------- mux ---------------- */
typedef struct {
    AVFormatContext *oc;
    int vi, ai;
} ff_mux;

ff_mux *ff_mux_open(const char *path, AVCodecContext *venc, AVCodecParameters *apar,
                    char *err, int errlen) {
    ff_mux *m = (ff_mux *)calloc(1, sizeof(ff_mux));
    if (!m) return 0;
    m->vi = m->ai = -1;
    int rc = avformat_alloc_output_context2(&m->oc, 0, "mp4", 0);
    if (!m->oc) { set_err(err, errlen, "alloc mp4 muxer", 0); free(m); return 0; }
    AVStream *vs = avformat_new_stream(m->oc, 0);
    if (!vs) { set_err(err, errlen, "new video stream", 0); goto fail; }
    rc = avcodec_parameters_from_context(vs->codecpar, venc);
    if (rc < 0) { set_err(err, errlen, "copy video params", rc); goto fail; }
    vs->time_base = venc->time_base;
    m->vi = vs->index;
    if (apar) {
        AVStream *as = avformat_new_stream(m->oc, 0);
        if (!as) { set_err(err, errlen, "new audio stream", 0); goto fail; }
        rc = avcodec_parameters_copy(as->codecpar, apar);
        if (rc < 0) { set_err(err, errlen, "copy audio params", rc); goto fail; }
        as->codecpar->codec_tag = 0;
        m->ai = as->index;
    }
    if (!(m->oc->oformat->flags & AVFMT_NOFILE)) {
        rc = avio_open(&m->oc->pb, path, AVIO_FLAG_WRITE);
        if (rc < 0) { set_err(err, errlen, "open output", rc); goto fail; }
    }
    rc = avformat_write_header(m->oc, 0);
    if (rc < 0) { set_err(err, errlen, "write header", rc); goto fail; }
    return m;
fail:
    if (m->oc) {
        if (m->oc->pb) avio_closep(&m->oc->pb);
        avformat_free_context(m->oc);
    }
    free(m);
    return 0;
}

int ff_mux_vstream(const ff_mux *m) { return m->vi; }
int ff_mux_astream(const ff_mux *m) { return m->ai; }
void ff_mux_tb(const ff_mux *m, int s, int *n, int *d) {
    AVRational tb = m->oc->streams[s]->time_base;
    if (n) *n = tb.num;
    if (d) *d = tb.den;
}
int ff_mux_write(ff_mux *m, AVPacket *pkt) { return av_interleaved_write_frame(m->oc, pkt); }
int ff_mux_close(ff_mux *m, int trailer, char *err, int errlen) {
    int rc = 0;
    if (trailer) rc = av_write_trailer(m->oc);
    if (m->oc->pb) avio_closep(&m->oc->pb);
    avformat_free_context(m->oc);
    free(m);
    if (rc < 0) set_err(err, errlen, "write trailer", rc);
    return rc;
}
