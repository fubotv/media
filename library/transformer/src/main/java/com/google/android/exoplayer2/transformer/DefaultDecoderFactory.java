/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.exoplayer2.transformer;

import static com.google.android.exoplayer2.util.Assertions.checkNotNull;
import static com.google.android.exoplayer2.util.Util.SDK_INT;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Pair;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MediaFormatUtil;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.ColorInfo;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** A default implementation of {@link Codec.DecoderFactory}. */
/* package */ final class DefaultDecoderFactory implements Codec.DecoderFactory {

  private final Context context;

  private final boolean decoderSupportsKeyAllowFrameDrop;

  public DefaultDecoderFactory(Context context) {
    this.context = context;

    decoderSupportsKeyAllowFrameDrop =
        SDK_INT >= 29
            && context.getApplicationContext().getApplicationInfo().targetSdkVersion >= 29;
  }

  @Override
  public Codec createForAudioDecoding(Format format) throws TransformationException {
    MediaFormat mediaFormat =
        MediaFormat.createAudioFormat(
            checkNotNull(format.sampleMimeType), format.sampleRate, format.channelCount);
    MediaFormatUtil.maybeSetInteger(
        mediaFormat, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
    MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);

    @Nullable
    String mediaCodecName = EncoderUtil.findCodecForFormat(mediaFormat, /* isDecoder= */ true);
    if (mediaCodecName == null) {
      throw createTransformationException(format);
    }
    return new DefaultCodec(
        context,
        format,
        mediaFormat,
        mediaCodecName,
        /* isDecoder= */ true,
        /* outputSurface= */ null);
  }

  @SuppressLint("InlinedApi")
  @Override
  public Codec createForVideoDecoding(
      Format format, Surface outputSurface, boolean requestSdrToneMapping)
      throws TransformationException {
    checkNotNull(format.sampleMimeType);
    if (ColorInfo.isTransferHdr(format.colorInfo)) {
      if (requestSdrToneMapping && (SDK_INT < 31 || deviceNeedsNoToneMappingWorkaround())) {
        throw createTransformationException(
            format, /* reason= */ "Tone-mapping HDR is not supported.");
      }
      if (SDK_INT < 29) {
        // TODO(b/266837571, b/267171669): Remove API version restriction after fixing linked bugs.
        throw createTransformationException(format, /* reason= */ "Decoding HDR is not supported.");
      }
    }

    MediaFormat mediaFormat =
        MediaFormat.createVideoFormat(format.sampleMimeType, format.width, format.height);
    MediaFormatUtil.maybeSetInteger(mediaFormat, MediaFormat.KEY_ROTATION, format.rotationDegrees);
    MediaFormatUtil.maybeSetInteger(
        mediaFormat, MediaFormat.KEY_MAX_INPUT_SIZE, format.maxInputSize);
    MediaFormatUtil.setCsdBuffers(mediaFormat, format.initializationData);
    MediaFormatUtil.maybeSetColorInfo(mediaFormat, format.colorInfo);
    if (decoderSupportsKeyAllowFrameDrop) {
      // This key ensures no frame dropping when the decoder's output surface is full. This allows
      // transformer to decode as many frames as possible in one render cycle.
      mediaFormat.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 0);
    }
    if (SDK_INT >= 31 && requestSdrToneMapping) {
      mediaFormat.setInteger(
          MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
    }

    @Nullable
    Pair<Integer, Integer> codecProfileAndLevel = MediaCodecUtil.getCodecProfileAndLevel(format);
    if (codecProfileAndLevel != null) {
      MediaFormatUtil.maybeSetInteger(
          mediaFormat, MediaFormat.KEY_PROFILE, codecProfileAndLevel.first);
    }

    @Nullable
    String mediaCodecName = EncoderUtil.findCodecForFormat(mediaFormat, /* isDecoder= */ true);
    if (mediaCodecName == null) {
      throw createTransformationException(format);
    }
    return new DefaultCodec(
        context, format, mediaFormat, mediaCodecName, /* isDecoder= */ true, outputSurface);
  }

  private static boolean deviceNeedsNoToneMappingWorkaround() {
    // Pixel build ID prefix does not support tone mapping. See http://b/249297370#comment8.
    return Util.MANUFACTURER.equals("Google")
        && (
        /* Pixel 6 */ Build.ID.startsWith("TP1A")
            || Build.ID.startsWith(/* Pixel Watch */ "rwd9.220429.053"));
  }

  @RequiresNonNull("#1.sampleMimeType")
  private static TransformationException createTransformationException(Format format) {
    return createTransformationException(format, "The requested decoding format is not supported.");
  }

  @RequiresNonNull("#1.sampleMimeType")
  private static TransformationException createTransformationException(
      Format format, String reason) {
    return TransformationException.createForCodec(
        new IllegalArgumentException(reason),
        TransformationException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        MimeTypes.isVideo(format.sampleMimeType),
        /* isDecoder= */ true,
        format);
  }
}
