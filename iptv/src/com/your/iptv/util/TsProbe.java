package com.your.iptv.util;

/**
 * MPEG-TS 元数据 demuxer —— 逐行移植自 rtp2httpd web-ui/src/playback-engine:
 *   demux/ts-demuxer.ts(parseChunks/handleSectionSlice/handlePESSlice/parsePES/
 *                      parsePAT/parsePMT/parseH264Payload/parseMP3Payload/parseADTSAACPayload)
 *   demux/sps-parser.ts(parseSPS)  demux/exp-golomb.ts(ExpGolomb)
 *   demux/annexb.ts(findAnnexBStartCodeOffset)  demux/aac.ts  demux/ac3.ts
 * 只移植元数据管线;解码交给 TV 硬件。数据源 = 本地回环泵转发中的播放流(单连接,与网页端一致)。
 */
public final class TsProbe {

    // ================= StreamType(pat-pmt-pes.ts) =================

    public static final int ST_MPEG1_AUDIO = 0x03;
    public static final int ST_MPEG2_AUDIO = 0x04;
    public static final int ST_PES_PRIVATE = 0x06;
    public static final int ST_ADTS_AAC = 0x0f;
    public static final int ST_LOAS_AAC = 0x11;
    public static final int ST_AC3 = 0x81;
    public static final int ST_EAC3 = 0x87;
    public static final int ST_H264 = 0x1b;
    public static final int ST_H265 = 0x24;
    public static final int ST_MPEG2_VIDEO = 0x02;

    // ================= PlayerMediaInfo(types.ts pipeline 组装) =================

    public static class Meta {
        public volatile String videoCodec;      // "H.264"/"HEVC"/"MPEG-2"
        public volatile String audioCodec;      // "AAC"/"MP2"/"MP3"/"AC-3"
        public volatile int presentWidth;       // 显示宽度 = ceil(编码宽 × SAR)
        public volatile int codecHeight;        // 编码高度
        public volatile String scanType;        // "interlaced"/"progressive"
        public volatile double fps;             // SPS timing_info
        public volatile int channels;
        public volatile int sampleRate;
        public volatile long bitrateBps;
        public volatile long bytesTotal;
    }

    private final Meta meta = new Meta();
    public Meta meta() { return meta; }

    private long fedBytes;

    /**
     * 元数据是否已齐(或喂入量已达 8MB 上限)。齐后泵线程应旁路解析:
     * 逐包 TS demux + PES 重组的分配/GC 开销跑在转发线程上,持续解析会造成
     * MediaPlayer 输入断流(马赛克+卡顿);网页端 /s/ 代理本就是纯转发。
     */
    public boolean metaComplete() {
        return (meta.videoCodec != null && meta.audioCodec != null
                && meta.codecHeight > 0 && meta.channels > 0)
                || fedBytes > (8L << 20);
    }

    // ---- 状态(ts-demuxer 字段)----
    private int currentPmtPid = -1;
    private boolean pmtDefined;
    private final java.util.HashMap<Integer, Integer> pidStreamType = new java.util.HashMap<Integer, Integer>();
    private int videoPid = -1, audioPid = -1;
    private boolean hasVideo, hasAudio;
    private boolean videoInitDispatched;
    private final java.util.HashMap<Integer, SliceQueue> pesSliceQueues = new java.util.HashMap<Integer, SliceQueue>();
    private final java.util.HashMap<Integer, SliceQueue> sectionSliceQueues = new java.util.HashMap<Integer, SliceQueue>();

    private String lastSpsMimetype;
    private boolean videoDone, audioDone;
    private int lastSpsPresentWidth = -1, lastSpsHeight = -1;

    // ---- SliceQueue(pat-pmt-pes.ts SliceQueue)----
    private static class SliceQueue {
        final java.util.ArrayList<byte[]> slices = new java.util.ArrayList<byte[]>();
        int totalLength;
        int expectedLength = -1;
    }

    // ================= parseChunks(ts-demuxer.ts) =================

    // ---- 跨块缓冲:泵的 TCP 切片边界与 188 包不对齐,必须自行累积重组 ----
    private final byte[] pend = new byte[512 * 1024];
    private int pendLen;
    private long dropCount;

    /** 喂入转发中的 TS 字节(TCP 切片,边界任意)。 */
    public void feed(byte[] chunk, int length) {
        fedBytes += length;
        // 1) 追加到跨块缓冲
        int take = Math.min(length, pend.length - pendLen);
        if (take < length) dropCount += length - take;      // 缓冲满:极端情况丢弃
        System.arraycopy(chunk, 0, pend, pendLen, take);
        pendLen += take;

        // 2) 同步 + 逐包解析
        int off = 0;
        while (true) {
            if (off + 188 > pendLen) break;                 // 尾部不完整,留待下次
            if (pend[off] != 0x47
                    || (off + 188 + 188 <= pendLen && pend[off + 188] != 0x47)) {
                off++;                                      // 找 sync(校验下一包 sync 防误判)
                continue;
            }
            parsePacket(pend, off);
            off += 188;
        }

        // 3) 压缩已消费部分
        if (off > 0) {
            System.arraycopy(pend, off, pend, 0, pendLen - off);
            pendLen -= off;
        }
        if (pendLen == pend.length) pendLen = 0;            // 全垃圾:重置
    }

    private void parseChunkLegacy(byte[] chunk, int length) {
        int offset = 0;
        while (offset + 188 <= length) {
            int syncByte = chunk[offset];
            if (syncByte != 0x47) break;                    // ts-demuxer:sync 错误即停

            int pusi = (chunk[offset + 1] & 0x40) >>> 6;
            int pid = ((chunk[offset + 1] & 0x1f) << 8) | chunk[offset + 2];
            int afc = (chunk[offset + 3] & 0x30) >>> 4;

            int tsPayloadStart = 4;
            if (afc == 0x02 || afc == 0x03) {
                int adaptationFieldLength = chunk[offset + 4] & 0xFF;
                if (afc == 0x02 || 5 + adaptationFieldLength == 188) {
                    offset += 188;                          // 仅 adaptation,无负载
                    continue;
                }
                tsPayloadStart = 4 + 1 + adaptationFieldLength;
            }

            if (afc == 0x01 || afc == 0x03) {
                int tsPayloadLength = 188 - tsPayloadStart;
                if (pid == 0 || pid == currentPmtPid) {
                    handleSectionSlice(chunk, offset + tsPayloadStart, tsPayloadLength, pid, pusi);
                } else if (pmtDefined && pidStreamType.containsKey(pid)) {
                    int streamType = pidStreamType.get(pid);
                    if (isMediaPid(pid)) {
                        handlePESSlice(chunk, offset + tsPayloadStart, tsPayloadLength,
                                pid, streamType, pusi);
                    }
                }
            }
            offset += 188;
        }
    }

    /** 单 TS 包处理(ts-demuxer 主循环体)。 */
    private void parsePacket(byte[] b, int off) {
        int pusi = (b[off + 1] & 0x40) >>> 6;
        int pid = ((b[off + 1] & 0x1f) << 8) | b[off + 2];
        int afc = (b[off + 3] & 0x30) >>> 4;

        int tsPayloadStart = 4;
        if (afc == 0x02 || afc == 0x03) {
            int adaptationFieldLength = b[off + 4] & 0xFF;
            if (afc == 0x02 || 5 + adaptationFieldLength == 188) return;
            tsPayloadStart = 4 + 1 + adaptationFieldLength;
        }

        if (afc == 0x01 || afc == 0x03) {
            int tsPayloadLength = 188 - tsPayloadStart;
            if (pid == 0 || pid == currentPmtPid) {
                handleSectionSlice(b, off + tsPayloadStart, tsPayloadLength, pid, pusi);
            } else if (pmtDefined && pidStreamType.containsKey(pid)) {
                int streamType = pidStreamType.get(pid);
                if (isMediaPid(pid)) {
                    handlePESSlice(b, off + tsPayloadStart, tsPayloadLength,
                            pid, streamType, pusi);
                }
            }
        }
    }

    private boolean isMediaPid(int pid) {
        return pid == videoPid || pid == audioPid;
    }

    // ================= handleSectionSlice(ts-demuxer.ts 原样) =================

    private void handleSectionSlice(byte[] buffer, int offset, int length, int pid, int pusi) {
        SliceQueue queue = sectionSliceQueues.get(pid);

        if (pusi != 0) {
            int pointerField = buffer[offset] & 0xFF;

            if (queue != null && queue.totalLength != 0) {
                int remain = Math.min(length - 1, pointerField);
                if (remain > 0) {
                    byte[] part = new byte[remain];
                    System.arraycopy(buffer, offset + 1, part, 0, remain);
                    queue.slices.add(part);
                    queue.totalLength += remain;
                }
                if (queue.totalLength == queue.expectedLength) {
                    emitSectionSlices(queue, pid);
                } else {
                    sectionSliceQueues.remove(pid);
                }
                queue = sectionSliceQueues.get(pid);
            }

            for (int i = 1 + pointerField; i < length; ) {
                if (i + 3 > length) break;              // JS data[i+2] 越界为 undefined,Java 需显式防护
                int tableId = buffer[offset + i] & 0xFF;
                if (tableId == 0xff) break;
                int sectionLength = ((buffer[offset + i + 1] & 0x0f) << 8) | buffer[offset + i + 2];

                queue = new SliceQueue();
                sectionSliceQueues.put(pid, queue);
                queue.expectedLength = sectionLength + 3;

                int remain = Math.min(length - i, queue.expectedLength - queue.totalLength);
                if (remain <= 0) break;                     // 边界防护(JS subarray 会钳位,Java 不会)
                byte[] part = new byte[remain];
                System.arraycopy(buffer, offset + i, part, 0, remain);
                queue.slices.add(part);
                queue.totalLength += remain;

                if (queue.totalLength == queue.expectedLength) {
                    emitSectionSlices(queue, pid);
                } else if (queue.totalLength > queue.expectedLength) {
                    sectionSliceQueues.remove(pid);
                }

                i += remain;                                // 网页端:i += remain_section.byteLength
            }
        } else if (queue != null && queue.totalLength != 0) {
            int remain = Math.min(length, queue.expectedLength - queue.totalLength);
            if (remain > 0) {
                byte[] part = new byte[remain];
                System.arraycopy(buffer, offset, part, 0, remain);
                queue.slices.add(part);
                queue.totalLength += remain;
            }
            if (queue.totalLength == queue.expectedLength) {
                emitSectionSlices(queue, pid);
            } else if (queue.totalLength > queue.expectedLength) {
                sectionSliceQueues.remove(pid);
            }
        }
    }

    private void emitSectionSlices(SliceQueue queue, int pid) {
        byte[] data = new byte[queue.totalLength];
        int off = 0;
        for (byte[] s : queue.slices) {
            System.arraycopy(s, 0, data, off, s.length);
            off += s.length;
        }
        queue.slices.clear();
        queue.expectedLength = -1;
        queue.totalLength = 0;
        parseSection(data, pid);
    }

    // ================= parseSection → parsePAT/parsePMT(ts-demuxer.ts) =================

    private void parseSection(byte[] data, int pid) {
        if (pid == 0x00) {
            parsePAT(data);
        } else if (pid == currentPmtPid) {              // ts-demuxer:parseSection 无 pmt_ 门控
            parsePMT(data);
        }
    }

    private void parsePAT(byte[] data) {
        if (data.length < 12 || (data[0] & 0xFF) != 0x00) return;   // table_id 0x00
        int sectionLength = ((data[1] & 0x0f) << 8) | data[2];
        int end = Math.min(3 + sectionLength - 4, data.length);
        for (int q = 8; q + 4 <= end; q += 4) {
            int program = ((data[q] & 0xFF) << 8) | (data[q + 1] & 0xFF);
            int pmtPid = ((data[q + 2] & 0x1F) << 8) | (data[q + 3] & 0xFF);
            if (program != 0 && currentPmtPid == -1) currentPmtPid = pmtPid;
        }
    }

    private void parsePMT(byte[] data) {
        if (data.length < 12 || (data[0] & 0xFF) != 0x02) return;   // table_id 0x02
        int sectionLength = ((data[1] & 0x0f) << 8) | data[2];
        int programInfoLength = (((data[10] & 0xFF) << 8) | (data[11] & 0xFF)) & 0x0FFF;
        int q = 12 + programInfoLength;
        int end = Math.min(3 + sectionLength - 4, data.length);
        while (q + 5 <= end) {
            int streamType = data[q] & 0xFF;
            int esPid = ((data[q + 1] & 0x1F) << 8) | (data[q + 2] & 0xFF);
            int esLen = ((data[q + 3] & 0x0F) << 8) | (data[q + 4] & 0xFF);
            pidStreamType.put(esPid, streamType);

            if ((streamType == ST_H264 || streamType == ST_H265) && videoPid == -1) {
                videoPid = esPid;
                if (meta.videoCodec == null) meta.videoCodec = displayVideoCodec(streamType);
            } else if (isAudioStreamType(streamType) && audioPid == -1) {
                audioPid = esPid;
                if (meta.audioCodec == null) meta.audioCodec = displayAudioCodec(streamType, 0);
            }
            q += 5 + esLen;
        }
        pmtDefined = true;                                  // pmt_ !== undefined 的等价门控
    }

    /** PMT stream_type → 显示名(player-media-badges formatVideoCodec 同映射)。 */
    private static String displayVideoCodec(int streamType) {
        if (streamType == ST_H264) return "h264";
        if (streamType == ST_H265) return "h265";
        if (streamType == ST_MPEG2_VIDEO) return "mpeg2";
        return null;
    }

    /** PMT stream_type → 显示名(formatAudioCodec 同映射;MP2/MP3 按网页端 sourceCodec 规则)。 */
    private static String displayAudioCodec(int streamType, int layer) {
        if (streamType == ST_ADTS_AAC || streamType == ST_LOAS_AAC) return "aac";
        if (streamType == ST_AC3) return "ac3";
        if (streamType == ST_EAC3) return "eac3";
        if (streamType == ST_MPEG2_AUDIO) return "mp2";
        if (streamType == ST_MPEG1_AUDIO) return layer == 2 ? "mp2" : "mp3";
        return null;
    }

    private static boolean isAudioStreamType(int t) {
        return t == ST_MPEG1_AUDIO || t == ST_MPEG2_AUDIO || t == ST_ADTS_AAC
                || t == ST_LOAS_AAC || t == ST_AC3 || t == ST_EAC3;
    }

    // ================= handlePESSlice / emitPESSlices / parsePES(ts-demuxer.ts 原样) =================

    private void handlePESSlice(byte[] buffer, int offset, int length, int pid, int streamType, int pusi) {
        int end = offset + length;
        int startPrefix = ((buffer[offset] & 0xFF) << 16) | ((buffer[offset + 1] & 0xFF) << 8)
                | (buffer[offset + 2] & 0xFF);
        int pesPacketLength = ((buffer[offset + 4] & 0xFF) << 8) | (buffer[offset + 5] & 0xFF);

        SliceQueue queue = pesSliceQueues.get(pid);
        if (pusi != 0) {
            if (startPrefix != 1) return;                   // ts-demuxer:错误即弃

            if (queue != null) {
                if (queue.expectedLength == 0 || queue.expectedLength == queue.totalLength) {
                    emitPESSlices(queue, pid, streamType);
                } else {
                    pesSliceQueues.remove(pid);
                }
            }
            queue = new SliceQueue();
            pesSliceQueues.put(pid, queue);
        }

        if (pesSliceQueues.get(pid) == null) return;        // 无 PUSI 起始:忽略

        queue = pesSliceQueues.get(pid);
        byte[] slice = new byte[end - offset];
        System.arraycopy(buffer, offset, slice, 0, end - offset);
        queue.slices.add(slice);
        if (pusi != 0) {
            queue.expectedLength = pesPacketLength == 0 ? 0 : pesPacketLength + 6;
        }
        queue.totalLength += slice.length;

        if (queue.expectedLength > 0 && queue.expectedLength == queue.totalLength) {
            emitPESSlices(queue, pid, streamType);
        } else if (queue.expectedLength > 0 && queue.expectedLength < queue.totalLength) {
            pesSliceQueues.remove(pid);
        }
    }

    private void emitPESSlices(SliceQueue queue, int pid, int streamType) {
        byte[] data = new byte[queue.totalLength];
        int off = 0;
        for (byte[] s : queue.slices) {
            System.arraycopy(s, 0, data, off, s.length);
            off += s.length;
        }
        queue.slices.clear();
        queue.expectedLength = -1;
        queue.totalLength = 0;
        parsePES(data, pid, streamType);
    }

    private void parsePES(byte[] data, int pid, int streamType) {
        if (data.length < 9) return;
        int startPrefix = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int streamId = data[3] & 0xFF;
        int pesPacketLength = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (startPrefix != 1) return;
        if (streamId == 0xBC || streamId == 0xBE || streamId == 0xBF || streamId == 0xF0
                || streamId == 0xF1 || streamId == 0xFF || streamId == 0xF2 || streamId == 0xF8) {
            return;
        }
        int pesHeaderDataLength = data[8] & 0xFF;
        int payloadStart = 6 + 3 + pesHeaderDataLength;
        int payloadLength;
        if (pesPacketLength != 0) {
            if (pesPacketLength < 3 + pesHeaderDataLength) return;
            payloadLength = pesPacketLength - 3 - pesHeaderDataLength;
            if (payloadStart + payloadLength > data.length) {
                payloadLength = data.length - payloadStart;
            }
        } else {
            payloadLength = data.length - payloadStart;
        }
        if (payloadLength <= 0) return;

        switch (streamType) {
            case ST_MPEG1_AUDIO:
            case ST_MPEG2_AUDIO:
                parseMP3Payload(data, payloadStart, payloadLength, streamType);
                break;
            case ST_PES_PRIVATE:
                if (audioPid == pid && ("AC-3".equals(meta.audioCodec) || meta.audioCodec == null)) {
                    parseAC3Payload(data, payloadStart, payloadLength);
                }
                break;
            case ST_ADTS_AAC:
                parseADTSAACPayload(data, payloadStart, payloadLength);
                break;
            case ST_AC3:
            case ST_EAC3:
                parseAC3Payload(data, payloadStart, payloadLength);
                break;
            case ST_H264:
                parseH264Payload(data, payloadStart, payloadLength);
                break;
            default:
                break;
        }
    }

    // ================= parseH264Payload(ts-demuxer.ts + h264.ts + annexb.ts) =================

    private void parseH264Payload(byte[] data, int payloadStart, int payloadLength) {
        // H264AnnexBParser:迭代 NAL(00 00 01 / 00 00 00 01)
        int end = payloadStart + payloadLength;
        int offset = findAnnexBStartCodeOffset(data, payloadStart, end);
        while (offset + 4 <= end) {
            // 起始码后为 NAL 头(type 在首个字节低 5 位)
            int nalHeaderOffset = offset + 3;
            if (data[offset + 2] == 0 && data[offset + 3] == 1) nalHeaderOffset = offset + 4;
            if (nalHeaderOffset >= end) break;
            int type = data[nalHeaderOffset] & 0x1F;

            if (type == 7) {                                // kSliceSPS
                int nalEnd = findAnnexBStartCodeOffset(data, nalHeaderOffset, end);
                int nalLen = (nalEnd >= end ? end : nalEnd) - nalHeaderOffset;
                SPSInfo info = parseSPS(data, nalHeaderOffset, nalLen);
                if (info != null) {
                    if (!hasVideo || metadataChanged(info)) {
                        applyVideoMetadata(info);
                        hasVideo = true;
                        videoInitDispatched = true;
                    }
                }
            }

            if (offset == nalHeaderOffset - 3) offset = nalHeaderOffset;   // 防死循环
            int next = findAnnexBStartCodeOffset(data, nalHeaderOffset, end);
            if (next <= offset) break;
            offset = next;
        }
    }

    private boolean metadataChanged(SPSInfo info) {
        return lastSpsPresentWidth != info.present_width
                || lastSpsHeight != info.codec_height
                || (lastSpsMimetype != null && !lastSpsMimetype.equals(info.codec_mimetype));
    }

    private void applyVideoMetadata(SPSInfo info) {
        meta.videoCodec = "h264";
        meta.presentWidth = info.present_width;
        meta.codecHeight = info.codec_height;
        meta.scanType = info.frame_mbs_only_flag == 0 ? "interlaced" : "progressive";
        meta.fps = info.frame_rate[1] > 0
                ? info.frame_rate[0] / (double) info.frame_rate[1] : 0;
        lastSpsMimetype = info.codec_mimetype;
        lastSpsPresentWidth = info.present_width;
        lastSpsHeight = info.codec_height;
    }

    // ================= findAnnexBStartCodeOffset(annexb.ts 原样) =================

    static int findAnnexBStartCodeOffset(byte[] data, int startOffset, int length) {
        int oneOffset = Math.max(2, startOffset + 2);
        while (oneOffset < length) {
            while (oneOffset < length && data[oneOffset] != 0x01) oneOffset++;
            if (oneOffset >= length) break;

            if ((data[oneOffset - 1] & 0xFF) != 0 || (data[oneOffset - 2] & 0xFF) != 0) {
                oneOffset++;
                continue;
            }

            int fourByteOffset = oneOffset - 3;
            int startCodeOffset =
                    fourByteOffset >= startOffset && data[fourByteOffset] == 0 ? fourByteOffset : oneOffset - 2;

            if (startCodeOffset >= startOffset && startCodeOffset + 3 < length) {
                return startCodeOffset;
            }
            oneOffset++;
        }
        return length;
    }

    // ================= SPSInfo + parseSPS(sps-parser.ts 原样移植) =================

    public static class SPSInfo {
        public String codec_mimetype;
        public int profile_idc, level_idc;
        public int chroma_format_idc;
        public int bit_depth_luma;
        public int frame_mbs_only_flag;
        public int[] frame_rate = new int[]{0, 0};           // {fpsNum, fpsDen}
        public int sar_width = 1, sar_height = 1;
        public int codec_width, codec_height;
        public int present_width;
    }

    private static byte[] ebsp2rbsp(byte[] src, int off, int len) {
        byte[] dst = new byte[len];
        int di = 0;
        for (int i = 0; i < len; i++) {
            if (i >= 2 && src[off + i] == 0x03 && src[off + i - 1] == 0x00 && src[off + i - 2] == 0x00) {
                continue;
            }
            dst[di++] = src[off + i];
        }
        byte[] out = new byte[di];
        System.arraycopy(dst, 0, out, 0, di);
        return out;
    }

    /** parseSPS(nalu):nalu 含 NAL 头 1 字节(sps-parser.ts 同构)。 */
    private SPSInfo parseSPS(byte[] uint8array, int off, int len) {
        if (len < 7) return null;
        byte[] rbsp = ebsp2rbsp(uint8array, off, len);
        ExpGolomb gb = new ExpGolomb(rbsp, rbsp.length);

        gb.readByte();                                       // NAL 头
        int profile_idc = gb.readByte();
        gb.readByte();                                       // constraint_set_flags + reserved
        int level_idc = gb.readByte();
        gb.readUEG();                                        // seq_parameter_set_id

        int chroma_format_idc = 1;
        int bit_depth_luma = 8;
        if (profile_idc == 100 || profile_idc == 110 || profile_idc == 122 || profile_idc == 244
                || profile_idc == 44 || profile_idc == 83 || profile_idc == 86
                || profile_idc == 118 || profile_idc == 128 || profile_idc == 138 || profile_idc == 144) {
            chroma_format_idc = gb.readUEG();
            if (chroma_format_idc == 3) gb.readBits(1);
            if (chroma_format_idc <= 3) {
                bit_depth_luma = gb.readUEG() + 8;
                gb.readUEG();                                // bit_depth_chroma_minus8
                gb.readBits(1);                              // qpprime_y_zero_transform_bypass
                if (gb.readBool()) {
                    int count = chroma_format_idc != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (gb.readBool()) {
                            skipScalingList(gb, i < 6 ? 16 : 64);
                        }
                    }
                }
            }
        }
        gb.readUEG();                                        // log2_max_frame_num_minus4
        int picOrderCntType = gb.readUEG();
        if (picOrderCntType == 0) {
            gb.readUEG();
        } else if (picOrderCntType == 1) {
            gb.readBits(1);
            gb.readSEG();
            gb.readSEG();
            int n = gb.readUEG();
            for (int i = 0; i < n; i++) gb.readSEG();
        }
        gb.readUEG();                                        // max_num_ref_frames
        gb.readBits(1);                                      // gaps_in_frame_num_value_allowed

        int picWidthInMbs = gb.readUEG();
        int picHeightInMapUnits = gb.readUEG();
        int frameMbsOnly = gb.readBits(1);
        if (frameMbsOnly == 0) gb.readBits(1);               // mb_adaptive_frame_field
        gb.readBits(1);                                      // direct_8x8_inference

        int cropL = 0, cropR = 0, cropT = 0, cropB = 0;
        if (gb.readBool()) {
            cropL = gb.readUEG(); cropR = gb.readUEG();
            cropT = gb.readUEG(); cropB = gb.readUEG();
        }

        int sarWidth = 1, sarHeight = 1;
        int fpsNum = 0, fpsDen = 0;

        if (gb.readBool()) {                                 // vui_parameters_present
            if (gb.readBool()) {                             // aspect_ratio_info_present
                int aspectRatioIdc = gb.readByte();
                int[] sarWTable = {1, 12, 10, 16, 40, 24, 20, 32, 80, 18, 15, 64, 160, 4, 3, 2};
                int[] sarHTable = {1, 11, 11, 11, 33, 11, 11, 11, 33, 11, 11, 33, 99, 3, 2, 1};
                if (aspectRatioIdc > 0 && aspectRatioIdc < 16) {
                    sarWidth = sarWTable[aspectRatioIdc - 1];
                    sarHeight = sarHTable[aspectRatioIdc - 1];
                } else if (aspectRatioIdc == 255) {
                    sarWidth = (gb.readByte() << 8) | gb.readByte();
                    sarHeight = (gb.readByte() << 8) | gb.readByte();
                }
            }
            if (gb.readBool()) gb.readBool();                // overscan
            if (gb.readBool()) {                             // video_signal_type
                gb.readBits(3);
                gb.readBool();
                if (gb.readBool()) { gb.readByte(); gb.readByte(); gb.readByte(); }
            }
            if (gb.readBool()) { gb.readUEG(); gb.readUEG(); } // chroma_loc
            if (gb.readBool()) {                             // timing_info_present
                int numUnitsInTick = gb.readBits(32);
                int timeScale = gb.readBits(32);
                gb.readBool();                               // fixed_frame_rate_flag
                fpsNum = timeScale;
                fpsDen = numUnitsInTick * 2;
            }
        }

        double sarScale = 1;
        if (sarWidth != 1 || sarHeight != 1) sarScale = sarWidth / (double) sarHeight;

        int cropUnitX, cropUnitY;
        if (chroma_format_idc == 0) {
            cropUnitX = 1;
            cropUnitY = 2 - frameMbsOnly;
        } else {
            int subWc = chroma_format_idc == 3 ? 1 : 2;
            int subHc = chroma_format_idc == 1 ? 2 : 1;
            cropUnitX = subWc;
            cropUnitY = subHc * (2 - frameMbsOnly);
        }

        int codecWidth = (picWidthInMbs + 1) * 16;
        int codecHeight = (2 - frameMbsOnly) * ((picHeightInMapUnits + 1) * 16);
        codecWidth -= (cropL + cropR) * cropUnitX;
        codecHeight -= (cropT + cropB) * cropUnitY;
        int presentWidth = (int) Math.ceil(codecWidth * sarScale);

        SPSInfo info = new SPSInfo();
        info.codec_mimetype = "avc1";
        info.profile_idc = profile_idc;
        info.level_idc = level_idc;
        info.chroma_format_idc = chroma_format_idc;
        info.bit_depth_luma = bit_depth_luma;
        info.frame_mbs_only_flag = frameMbsOnly;
        info.frame_rate[0] = fpsNum;
        info.frame_rate[1] = fpsDen;
        info.sar_width = sarWidth;
        info.sar_height = sarHeight;
        info.codec_width = codecWidth;
        info.codec_height = codecHeight;
        info.present_width = presentWidth;
        return info;
    }

    private static void skipScalingList(ExpGolomb gb, int count) {
        int last = 8, next = 8;
        for (int i = 0; i < count; i++) {
            if (next != 0) {
                int delta = gb.readSEG();
                next = (last + delta + 256) % 256;
            }
            last = next == 0 ? last : next;
        }
    }

    // ================= parseMP3Payload 头部(ts-demuxer.ts 原样位提取) =================

    private static final int[] SF_V10 = {44100, 48000, 32000, 0};
    private static final int[] SF_V20 = {22050, 24000, 16000, 0};
    private static final int[] SF_V25 = {11025, 12000, 8000, 0};

    private void parseMP3Payload(byte[] data, int payloadStart, int payloadLength, int streamType) {
        if (payloadLength < 4) return;
        // A payload may start mid-frame: 只信从帧头开始的载荷(ts-demuxer sync_at_start)
        if (data[payloadStart] != (byte) 0xFF || (data[payloadStart + 1] & 0xE0) != 0xE0) return;

        int ver = (data[payloadStart + 1] >>> 3) & 0x03;
        int layer = (data[payloadStart + 1] & 0x06) >> 1;
        int sfIdx = (data[payloadStart + 2] & 0x0c) >>> 2;
        int channelMode = (data[payloadStart + 3] >>> 6) & 0x03;
        int channelCount = channelMode != 3 ? 2 : 1;

        int sampleRate;
        if (ver == 0) sampleRate = SF_V25[sfIdx];
        else if (ver == 2) sampleRate = SF_V20[sfIdx];
        else if (ver == 3) sampleRate = SF_V10[sfIdx];
        else return;                                         // reserved

        // 网页端:object_type 33(Layer 2)/stream_type 4 → 软解标记 'mp2'
        String codec = streamType == ST_MPEG2_AUDIO || layer == 2 ? "mp2" : "mp3";
        meta.audioCodec = codec;
        meta.channels = channelCount;
        meta.sampleRate = sampleRate;
        audioDone = true;
    }

    // ================= parseADTSAACPayload 头部(aac.ts) =================

    private static final int[] ADTS_SF = {96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private void parseADTSAACPayload(byte[] data, int payloadStart, int payloadLength) {
        if (payloadLength < 7) return;
        int off = payloadStart;
        if (((data[off] & 0xFF) << 8 | (data[off + 1] & 0xFF)) >>> 4 != 4095) return;
        int sfIdx = (data[off + 2] & 0x3c) >>> 2;
        int channelConfig = ((data[off + 2] & 0x01) << 2) | ((data[off + 3] & 0xc0) >>> 6);
        if (sfIdx >= ADTS_SF.length || channelConfig == 0) return;
        meta.audioCodec = "aac";
        meta.channels = channelConfig;
        meta.sampleRate = ADTS_SF[sfIdx];
        audioDone = true;
    }

    // ================= parseAC3Payload 头部(ac3.ts AC3Parser) =================

    private static final int[] AC3_SF = {48000, 44100, 32000};

    private void parseAC3Payload(byte[] data, int payloadStart, int payloadLength) {
        if (payloadLength < 8) return;
        int off = payloadStart;
        int sync = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
        if (sync != 0x0B77) return;
        int sfCode = (data[off + 4] >> 6) & 0x03;
        if (sfCode >= AC3_SF.length) return;
        int acmode = (data[off + 6] >> 5) & 0x07;
        int lfeOn = (((data[off + 6] & 0xFF) << 8) | (data[off + 7] & 0xFF)) >> 12 & 0x01;
        meta.audioCodec = "ac3";
        meta.channels = new int[]{2, 1, 2, 3, 3, 4, 4, 5}[acmode] + lfeOn;
        meta.sampleRate = AC3_SF[sfCode];
        audioDone = true;
    }

    // ================= ExpGolomb(exp-golomb.ts 原样,字读取) =================

    public static class ExpGolomb {
        private final byte[] buffer;
        private int bufferIndex;
        private final int totalBytes;
        private int currentWord;
        private int currentWordBitsLeft;

        public ExpGolomb(byte[] uint8array, int length) {
            buffer = uint8array;
            bufferIndex = 0;
            totalBytes = length;
        }

        private void fillCurrentWord() {
            int bytesLeft = totalBytes - bufferIndex;
            if (bytesLeft <= 0) throw new IllegalStateException("ExpGolomb: no bytes available");
            int bytesRead = Math.min(4, bytesLeft);
            int word = 0;
            for (int i = 0; i < bytesRead; i++) {
                word = (word << 8) | (buffer[bufferIndex + i] & 0xFF);
            }
            currentWord = word;
            bufferIndex += bytesRead;
            currentWordBitsLeft = bytesRead * 8;
        }

        public int readBits(int bits) {
            if (bits <= currentWordBitsLeft) {
                int result = currentWord >>> (32 - bits);
                currentWord <<= bits;
                currentWordBitsLeft -= bits;
                return result;
            }
            int result = currentWordBitsLeft != 0 ? currentWord : 0;
            result = result >>> (32 - currentWordBitsLeft);
            int bitsNeedLeft = bits - currentWordBitsLeft;
            fillCurrentWord();
            int bitsReadNext = Math.min(bitsNeedLeft, currentWordBitsLeft);
            int result2 = currentWord >>> (32 - bitsReadNext);
            currentWord <<= bitsReadNext;
            currentWordBitsLeft -= bitsReadNext;
            return (result << bitsReadNext) | result2;
        }

        public boolean readBool() { return readBits(1) == 1; }

        public int readByte() { return readBits(8); }

        private int skipLeadingZero() {
            int zeroCount;
            for (zeroCount = 0; zeroCount < currentWordBitsLeft; zeroCount++) {
                if (0 != (currentWord & (0x80000000 >>> zeroCount))) {
                    currentWord <<= zeroCount;
                    currentWordBitsLeft -= zeroCount;
                    return zeroCount;
                }
            }
            fillCurrentWord();
            return zeroCount + skipLeadingZero();
        }

        public int readUEG() {
            int leadingZeros = skipLeadingZero();
            return readBits(leadingZeros + 1) - 1;
        }

        public int readSEG() {
            int value = readUEG();
            if ((value & 0x01) != 0) return (value + 1) >>> 1;
            return -1 * (value >>> 1);
        }
    }

    // ================= 兼容旧接口(JVM 测试) =================

    public static int[] parse(byte[] buf, int len) {
        TsProbe p = new TsProbe();
        p.feed(buf, len);
        int v = 0, a = 0;
        if (p.meta.videoCodec != null) v = codecId(p.meta.videoCodec);
        if (p.meta.audioCodec != null) a = codecId(p.meta.audioCodec);
        return new int[]{v, a};
    }

    private static int codecId(String name) {
        if ("h264".equals(name)) return 0x1B;
        if ("h265".equals(name)) return 0x24;
        if ("mpeg2".equals(name)) return 0x02;
        if ("aac".equals(name)) return 0x0F;
        if ("ac3".equals(name)) return 0x81;
        if ("mp2".equals(name) || "mp3".equals(name)) return 0x03;
        return 0;
    }

    public static String codecName(int t) {
        switch (t) {
            case 0x1B: return "H.264";
            case 0x24: return "HEVC";
            case 0x02: return "MPEG-2";
            case 0x0F: return "AAC";
            case 0x11: return "HE-AAC";
            case 0x81: case 0x83: return "AC-3";
            case 0x03: case 0x04: return "MP2";
            default: return "type 0x" + Integer.toHexString(t);
        }
    }
}
