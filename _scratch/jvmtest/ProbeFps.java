import com.your.iptv.util.TsProbe;
import java.io.FileInputStream;

/** 用真实 TS 样本验证 TsProbe 的 SPS/帧率解析,定位信息条"帧率 —"。 */
public class ProbeFps {
    public static void main(String[] args) throws Exception {
        TsProbe p = new TsProbe();
        FileInputStream in = new FileInputStream(args[0]);
        byte[] buf = new byte[32 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            p.feed(buf, n);
            total += n;
            TsProbe.Meta m = p.meta();
            if (total <= 128 * 1024 || (total / 32768) % 4 == 0) {
                System.out.println("fed=" + total + " fps=" + m.fps
                        + " video=" + m.videoCodec + " audio=" + m.audioCodec
                        + " h=" + m.codecHeight + " ch=" + m.channels
                        + " complete=" + p.metaComplete());
            }
        }
        in.close();
        TsProbe.Meta m = p.meta();
        System.out.println("FINAL fed=" + total + " fps=" + m.fps
                + " video=" + m.videoCodec + " audio=" + m.audioCodec
                + " h=" + m.codecHeight + " ch=" + m.channels
                + " scan=" + m.scanType);
    }
}
