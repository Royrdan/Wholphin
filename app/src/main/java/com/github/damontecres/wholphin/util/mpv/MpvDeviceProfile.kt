package com.github.damontecres.wholphin.util.mpv

import com.github.damontecres.wholphin.util.profile.Codec
import com.github.damontecres.wholphin.util.profile.subtitleProfile
import com.github.damontecres.wholphin.util.profile.supportedAudioCodecs
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

val mpvDeviceProfile =
    buildDeviceProfile {
        name = "mpv"

        // TODO
//    maxStaticBitrate = maxBitrate
//    maxStreamingBitrate = maxBitrate
        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING

            container = Codec.Container.TS
            protocol = MediaStreamProtocol.HLS

            videoCodec(Codec.Video.HEVC)
            videoCodec(Codec.Video.H264)

            audioCodec(*supportedAudioCodecs)

            copyTimestamps = false
            enableSubtitlesInManifest = true
        }
        directPlayProfile {
            type = DlnaProfileType.VIDEO

            container(
                Codec.Container.`3GP`,
                Codec.Container.ASF,
                Codec.Container.AVI,
                Codec.Container.DASH,
                Codec.Container.DVR_MS,
                Codec.Container.HLS,
                Codec.Container.M2V,
                Codec.Container.M4V,
                Codec.Container.MKV,
                Codec.Container.MOV,
                Codec.Container.MP3,
                Codec.Container.MP4,
                Codec.Container.MPEG,
                Codec.Container.MPEGTS,
                Codec.Container.MPG,
                Codec.Container.OGM,
                Codec.Container.OGV,
                Codec.Container.TS,
                Codec.Container.VOB,
                Codec.Container.WEBM,
                Codec.Container.WMV,
                Codec.Container.WTV,
                Codec.Container.XVID,
            )

            videoCodec(
                Codec.Video.AV1,
                Codec.Video.H264,
                Codec.Video.HEVC,
                Codec.Video.MPEG,
                Codec.Video.MPEG2VIDEO,
                Codec.Video.VC1,
                Codec.Video.VP8,
                Codec.Video.VP9,
            )

            audioCodec(
                *supportedAudioCodecs,
                Codec.Audio.WAV,
                Codec.Audio.OGG,
                Codec.Audio.WMA,
                Codec.Audio.WMAV2,
                Codec.Audio.APE,
                Codec.Audio.WEBMA,
                Codec.Audio.OGA,
                Codec.Audio.MPA,
                Codec.Audio.PCM,
            )
        }

        directPlayProfile {
            type = DlnaProfileType.AUDIO

            audioCodec(
                *supportedAudioCodecs,
                Codec.Audio.WAV,
                Codec.Audio.OGG,
                Codec.Audio.WMA,
                Codec.Audio.WMAV2,
                Codec.Audio.APE,
                Codec.Audio.WEBMA,
                Codec.Audio.OGA,
                Codec.Audio.MPA,
                Codec.Audio.PCM,
            )
        }

        subtitleProfile(Codec.Subtitle.VTT, embedded = true, hls = true, external = true)
        subtitleProfile(Codec.Subtitle.WEBVTT, embedded = true, hls = true, external = true)
        subtitleProfile(Codec.Subtitle.SRT, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.SUBRIP, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.TTML, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.DVBSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.DVDSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.IDX, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.PGS, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.PGSSUB, embedded = true, encode = true)
        subtitleProfile(Codec.Subtitle.ASS, encode = true, embedded = true, external = true)
        subtitleProfile(Codec.Subtitle.SSA, encode = true, embedded = true, external = true)
    }
