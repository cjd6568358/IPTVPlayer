import com.your.iptv.source.Channel;
import com.your.iptv.source.M3uParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** V1.3 新特性 JVM 测试:多线路合并/$标签 + TS 探针 PAT/PMT 解析。 */
public class Test317 {

    static int failed = 0;

    static void eq(String name, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : ": want=" + want + " got=" + got));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        // ---- 多线路合并 ----
        List<Channel> a = M3uParser.parse(
                "#EXTM3U\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV-1\n"
                + "http://x/1/线路1$线路1\n"
                + "http://x/1/线路2$高清\n"
                + "#EXTINF:-1 group-title=\"央视\",CCTV-2\n"
                + "http://x/2\n");
        eq("T1 合并为 2 频道", 2, a.size());
        eq("T1 CCTV-1 两线路", 2, a.get(0).sourceCount());
        eq("T1 标签1", "线路1", a.get(0).labels.get(0));
        eq("T1 标签2($高清)", "高清", a.get(0).labels.get(1));
        eq("T1 默认线路", "http://x/1/线路1", a.get(0).getUrl());
        eq("T1 编号重排(频道1)", 1, a.get(0).number);
        eq("T1 编号重排(频道2)", 2, a.get(1).number);
        eq("T1 单线路频道", 1, a.get(1).sourceCount());
        eq("T1 无$标签默认名", "线路1", a.get(1).getLabel());

        // 线路切换
        eq("T2 切到线路2", true, a.get(0).cycleSource(1));
        eq("T2 切后 URL", "http://x/1/线路2", a.get(0).getUrl());
        eq("T2 切后标签", "高清", a.get(0).getLabel());
        eq("T2 环绕回线路1", true, a.get(0).cycleSource(1));
        eq("T2 环绕 URL", "http://x/1/线路1", a.get(0).getUrl());
        eq("T2 单线路不切换", false, a.get(1).cycleSource(1));

        // 同名不同组不合并
        List<Channel> b = M3uParser.parse(
                "#EXTINF:-1 group-title=\"央视\",新闻台\nhttp://x/a\n"
                + "#EXTINF:-1 group-title=\"卫视\",新闻台\nhttp://x/b\n");
        eq("T3 不同组同名不合并", 2, b.size());

        // ---- TS 探针解析 ----
        byte[] ts = buildTs();
        Class<?> pa = Class.forName("com.your.iptv.util.TsProbe");
        Method m = pa.getDeclaredMethod("parse", byte[].class, int.class);
        m.setAccessible(true);
        int[] r = (int[]) m.invoke(null, ts, ts.length);
        Method cm = pa.getDeclaredMethod("codecName", int.class);
        cm.setAccessible(true);
        eq("T4 视频编码 = H.264 (0x1B)", "H.264", cm.invoke(null, r[0]));
        eq("T4 音频编码 = AAC (0x0F)", "AAC", cm.invoke(null, r[1]));
        // 空数据不崩
        int[] r2 = (int[]) m.invoke(null, new byte[0], 0);
        eq("T5 空输入安全", 0, r2[0]);

        // ---- T6/T7:真实流样本(贵州卫视 1080p,H.264 High + MP2)----
        {
            byte[] spsNal = hex("67640033acd94078044fde022000000300200000065c0c0003d12c000f427049807c58b65800");
            Class<?> tp = Class.forName("com.your.iptv.util.TsProbe");
            Object probeObj = tp.getDeclaredConstructor().newInstance();
            Method ps = tp.getDeclaredMethod("parseSPS", byte[].class, int.class, int.class);
            ps.setAccessible(true);
            Object info = ps.invoke(probeObj, spsNal, 0, spsNal.length);
            if (info == null) {
                eq("T6 SPS 解析成功", "ok", "null");
            } else {
                java.lang.reflect.Field fpw = info.getClass().getField("present_width");
                java.lang.reflect.Field fch = info.getClass().getField("codec_height");
                java.lang.reflect.Field ffr = info.getClass().getField("frame_rate");
                int[] fr = (int[]) ffr.get(info);
                System.out.println("  SPS: present=" + fpw.get(info) + "×" + fch.get(info)
                        + " fps=" + fr[0] + "/" + fr[1] + " mbsOnly=" + info.getClass().getField("frame_mbs_only_flag").get(info));
                eq("T6 显示宽 1920", 1920, ((Integer) fpw.get(info)).intValue());
                eq("T6 编码高 1080", 1080, ((Integer) fch.get(info)).intValue());
                eq("T6 帧率 25", 25, (int) Math.round(fr[0] / (double) fr[1]));
            }
            byte[] au = hex("fff2e786930958d21df120d55194fced7d9b7384ddb65730621a23820577e506a9381dcf995ca085d842c120ab830952a577edf545ccb365124f7c6fd48820587889e00e85471022120421117c00ba2803364729c1a2aaf7c3420aad8b3177528b90e3000e10efe456ade112f561aec301bec882cd14c9ebd739d158508b07b99799ff0f9e74dcab1f7823188c53a74c9867919ccaaae187e551f49bd2843f7bb3032b23c9b7efb717cb690578b1ea2c3eda0c3f958cd914a432c59dbe8f82b2653f81bec7201dba610ae2255db6edca1cf3856222b9d2ee45571151ad29272090f780fc3085fa54059197ec1d7ba7b08fab0d14ad20cd942a31dc32f4c9f30c88471022138d18eba8d34e913291cd1f8cd29de9fe6f2a0a7a404c8174f603df4e252dcc251cf26b66c2aecb9682711b63c204c42cbb0ca97899f00d57e0301eb5df38b544c5396012dc4e681a6e67175ed8f24c6d7380567e24b891922179e2eaf787e39489c409d033f00e024a30c10a4fe43c1a298ef10157f3b2b80714f7ea2908395f174df642bd22516830f80739d8252a60be9bc730ade90dd4a6cc7ac20eefaa1b9dcb96ecd98922baa497d2aafb7e87acb0139b3ea8a6ee9d47102318730de953e0cf958a199d48f5515ce3d96c7b50d69b619e64b78d2e6a224da1d52d6614d5e59e665dbb68e455c4a985cd5e44db26536b88511d16629859d557907dd2619539a363cc2543e17a3615639562e8d2a5b31972f1660221a8999e9919a92156f6c48b24370b7d21261d65af68de36d72213c319d650a5dbd595c7ed756d636335e174745eb8f82dd991bc194505d9dd99e0dea5512de265d155b2a0b972bcf0fad9e96615a15cbc0a09a28b9d9d98eca943f6f119471022142434359d3ce95c8c2f2e64d87ab5e41610ebe3a11bdc66771fdea8e25b7a63c82db1b7d2ad45d7cb3dcd2fd7c6606b5d10d8b62ae1713a0822366060c6793fce16d89cd62e376b6abef530cf9deb73ba45c3599619a3ffb5176ad1fa1d1faeb689286a55b328f6dbe856fd5cd16528274d44bcb5b75255155d2a911abaa53001d9f5288eb53d8284d2d27ebb5e235700f924c24e24adcc9c5ae0257bb39fdfea67962691023cf6f3e06e0ca562a9bebf6dc6eb5db61670d94710221569f1f82a34120652682c0f0ce1494e35c83b4100712a756406ff5f776dca7e6f7a5067c83cc7727050408fc0ae32ecefa9552a4d80440cade222796843678cf5f25e61e06f2b043218f9350e7c9e523460de886ff7506310207d1fc0a12c43d454a94d9abf9ac345896a53cacb7310d5c4368c425e1cf2931f47b0e208e190cf7fb4e796ee6f6ad7365855e873510cc53ef5d9994a0e280b852650238b69e3effb542b4cc6e329a9f6be5c80fb9862544ed142d48f1587a7471022160ac32d57231fd45ba2a3718c0820bb2c648be337414fc09c15383c8d276f875fa1f978c1acde39a101356af86f0992f1bd29e6fbb701f396f3865d40615cea9d3faa159866e2660e217cc0eac2b899cc4ac79c58faffde29db78367a4829541a5b0964594a730c327bbeff68153523a2a79f42ee99c4e5db2094c89b0cadf12b6476e32591b35f1363f5bfc9cc906ea052bbd173eb54add5e742dc0dfd08a81b16286f0590401d972f99647b75b5eb58cfbbb5035ae632544710221797507688f54002f743c9ecf25b2e7f55df9f34e2c8f1978501c30d04bbe88a8de8763dab33b80b4d445b036dfa97abf977b41d7e1aa5d1565bb29f0d0adcfce02486f53539a367f3c803266405e05dca2211a1bb5214b4a5fff8ee0f32f0a27dadbb082e0cda5b7941d6e57eb75b05d85cf801e107e2653b06ec3db81ab39e659b714ccb5bc126a2549456c80e29a38d36a7f71a94cacd90f65b6343731aa9a813e6a56da3af2e04515169bf56750a83a4e3258115825a4d47102218ef53dcac944e0ee0477262bc6c89ee82e51b91aac64efe1b0a05402d8bb952acba9dc688d0949a7ef511e4adf6df37415285032ee4b6cede1c0b697c9caef24e2418f8ccc5e908e7c7c0a1d6e871dc91a6ff14f25e349c1c089ee7796515a4fd0b18d393687f3822af39e557364833c418a1dc68ed058450acaa4d96c98f2ca589e85f2a5c8bb47c6555398f233edafc2d975418445572f3ce1b548379c826b0985e2766440c77628da48f3cd9524b5e0a97c7cba059b0a547102219942beb550c785274cbbe8ec897449d3a175fc6488c997de651adf40bb3b451e5e5d4acc8fc955120a1599a2c755f7c2ca723a3df0047a5081651e2386a38d5e06543b632db69a6e05570783e4776efd3cb525dfb450cbef94c8e4a8fbcd4169419729f7891ba728ce85061f2506152d8eb6c51a1cca272fbb0c901e5907014ce85908afdc9d18d48bf99de2e43dee7be44c7095293f390125b772bd7cee0e25b06dbc0c92059be9390103d816c99e1bdec6902a93f8191c24710221a7915fb2b5895b5635340a32072ce95bc37801441019abb7a2e6e733d97f3d56cfe263b63dc3606596d9866d6664d3850f2baa6b0a3a11c153311ed85063739d58ae7b719d7cef1794c54476e372bf975f5e52471421f14a8bcd283432fc717706bea01817db9ec0f62a26cb7411e1ff38b0088a28d117987683f510f75cfe879452a32724291ca7c901e373708b9208964be4af8d2ebcdf0bcaaf81f2ecf5bc305f5507a55b86fdef019616bc2e68f20e345ef89339719494710221bd589e3841fcd5371081aa234065c81dc42fdb16ed44364abb2b3749361fe0eeace9042c1e7a50897656598030291a0260564a0178b9014cd79c33fa8cd43bdaaba11dc634d35d014092c6959615a7dfc0339913f63c49c8f564fa5b0476a8f0d718fe52311e5b37576b6c7dd5597437a0800b1b1d6c219d94f697aa0d3d98b09d72f151ca305da85b4adf3f6ab477a574c0c2fd51848f449ff3f5cfcec8b6ad2872e4ce3843dbdc4da0524355747e2a4ca3f709e322b9d1a4710221c463bf3c726e894b286e2a247b262602e7c5d8d02fd6e7dd327ed599c1e260b06cee5789affc283f6b3ad0305269afe1fa1fffac75836477d5b4f192ef960b7730c852fdb6d83ca1915401242d304ce0f9327c03f243d3573f0f931d3b57051a3837417aa637069fd38a744a1681a9e8c0746bb8fe8e90e847d31710042f8b69b4ad2f693f8864e7a2f2d82ba9846b8438cedd48fa66f4b670ce430bbb301eb9ec831b16ef46d4558f1abcdb82e6597319b7eef83f82d0a504710221d7ad7eb3cd8f139c77b54f8fbba0ca6ffdacacf31b13fd41af7bb84b4c299f8a61bd62d8ae8a0a410315c8ea9ff593051558fffd29628c537de9e34bdf2fe92d2d1d0f062eae5f2b7926fb436766edc88d953e57a24356bb6a7bf2b254439f51a83680cfe43ce95feb023979ab1c6cac7efa30b6f855309a88da2b891941655744daa8bc5270ac57fb7026b1e1b2be3c693228cf4dbea9016f6c866d04d2a7a8397cf259aa3b6bf4f309ba652332c5451cdeb650e5a30e27d4710221eddbffa16beb45d37a57fb5d5c6c969f5bd7d0a87b9c2b14cf2163c204df63e340528ab94e76a6fcdecbdc96550fb18c0da40a85f026e2bef86c0aa5e8f4475307b0886d7d6ac127ffbd3dba91c8c9d2f87e896d3d32e0c36394351e8623080383e46f58eda04fa183c93acca01a1ed076a781e9e8a7052791571e4cf6b7fc82f85536ddd6ff63e5a0af71beacb3d07524b504ac2685edda00a723d3213ca797aebc7e21f3eed7fc448777a798f1fe98a22a48a9677a3618a4710221f15f666fc36aeee6005d55487a23501f6209f5426f8d3b258466603dd1264ebc6c16d02f0f34403c1f770663a0f430bd3f538716b43856d24abb0784bab541bc923c9d6c124dc9c9f3d5c30de4311d0dfb614fa29d43714197040ce8d0993de59d5cda2013b115dfdd60b5a3b57ca5cf796ab273110e74a6d1bb8aea6616aab7ea829efd956d547a5cf43a8704b73b9eb961e6e426dd78d2b24abdfb4bc50097ba274e1a5e6d8fd89c5cce34a45ee8a23f90c69f0ba1ea433471022108cb9faa95d3e32c7d90d5d39640b62921b2189cbe4bf0f9c83e8710263f04f7928a1ef3119d42aed7c9a2d6b205eb852041c4f0fca94682cd0969f9f5c6c10a9110e144a39cfebfe63fd8868c5e402624e57694a50e3d2be91d199ec01688056d037fb3047acbd5e7fb4a5859c6ed347a933403a3c4464667f428fdab642002c89a5239d50afc8ce1f9b522f442142a3aac30037edd301d52dfe64af29899fe96a991f3e719f8dc9ed73aaf3fb911683808bcf029fa71e0147102211073a743efa1c6ef81f6c60b0169cde23954867fb93a9e5254ef80702a1ee56766dfafaa5c45003f8a768108754a10ab675ed662a438165414eda94f4d2bd63186ca7fe22148f5c0447f6e1bdf0fa5aa632b4e93e0f47fd354896191cec18423afa2f5959cff268905c59fb86b1f9f303ff912cd0e476c353a841857911302ed698216c82b3150d3a69a9a2d9b7cd2b5cc5d8fe69ab92a89c36b54d8578d94413278aa1fdaeb5e8ae7300dc15ddd9252bf0373737c88e51ac47102212e4dc7bf221e9e2a6823a33e9656b396b017af1143740b4de2a73ed43f4e15ffa106b49ad1a260e34706522ee70b6432efb30349054ea4e622db0f0470288be8c838c26f177986043e3df042c379f809ecc0bc2b49ee426bd206b0042739a5173063458df03fb3cd2d42b25a2396f32940cab3a7774da6404447e6cb6f300a522e9dcdacf58574161b3819683fed20cd128644acf86328b21d0f0b9d2281d264456b8bf45b8a32d00d938ce608dba0c302ec7254ec09ec93e4710221335f91dce480321a42c292c313be65dd57bdbcf6a64f0502bd7adc19113ef7bcf3a59aca84cd50c1e4a0476728d4cd68e0f9662e9f40f388d33201691830dd6eb49da46216d9b95a34b88dca09e6313d0ce395b1e5756419c7c053048ebf81d92eeb27e35df2e5abe5c4adca62fcd1b39588211374a6e8cf80620d2cbad9c202fcc4071bd897d5809ebab6e040b2c6c28ac8962651bea6e3606a640400ddbef9d4b62531c069e67cd5b45f74abd97ec3a09fb4a729315ffc5471022147b5eaf13165b87bc873fe99d5377882fecef294d7a08e23e54de7cef9aa0be66641d43b509e98dacdbc6a55ee27b6e01bc2ce82d637ab314a7f440fc7446c70f1b24e1110b65fc372d77d09630cb16cf11a7821f9ba9810db6cd8febecdef55fafd24c5ba879058ca880fa60be4dec62921659c23c8050d628d66a77850d3dcac196fb04300a014f47e440e88543f664a517cda03c1489b753ae1fad5957638661354705f8de2289a333d0df9ec39ab97ed99ae388a0c6464710221589b88f9da4043dd1d1873db691fc55a9d2f6a40fcaac3413184814a3c4c65043e1aeb5440c47a7d2ece8b23ab6f783044543cbe9fb71a713a33bcd41e5d3d90fd58c65b2935289f9202a4b1a59eaf7040fbede9bce0dbfac370afd05c6c6172904ed62840c57a640bf0810954976bdeaf5811129cab3ba8d0df9b32dcced27be1645deb8cb183fcd34b85251f0fc342faf86f78b625383431adaa3d45124880ebb87ba1ab7aaeeb70b6187b4df82b525e523ec4b9212a4ce4710221685995de7f8e60defe80d0c90458a0ec477ff5ce514f4175a85148f3cd20cff4b4cc72055342baeff5e425f6ff0cdb760a0b930170113fd28a6832d04085c13f86ebf2bbb5bca559c43a6bd");
            Method ma = tp.getDeclaredMethod("parseMP3Payload", byte[].class, int.class, int.class, int.class);
            ma.setAccessible(true);
            ma.invoke(probeObj, au, 0, au.length, 4);
            Method gm = tp.getMethod("meta");
            Object meta = gm.invoke(probeObj);
            java.lang.reflect.Field fc = meta.getClass().getField("channels");
            java.lang.reflect.Field fs = meta.getClass().getField("sampleRate");
            java.lang.reflect.Field fcc = meta.getClass().getField("audioCodec");
            System.out.println("  AUDIO: channels=" + fc.get(meta) + " sampleRate=" + fs.get(meta)
                    + " codec=" + fcc.get(meta));
            eq("T7 MP2 双声道", 2, ((Integer) fc.get(meta)).intValue());
            eq("T7 codec mp2(stream_type 4)", "mp2", (String) fcc.get(meta));
        }

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILURES");
        if (failed > 0) System.exit(1);
    }

    /** 构造最小 TS:1 个 PAT(PID 0 → PMT PID 0x100)+ 1 个 PMT(H.264@0x101, AAC@0x102)。 */
    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    static byte[] buildTs() {
        byte[] pat = new byte[188];
        pat[0] = 0x47; pat[1] = 0x40; pat[2] = 0x00; pat[3] = 0x10;  // PUSI,PID0,payload-only
        pat[4] = 0x00;                                    // pointer_field
        pat[5] = 0x00;                                    // table_id = PAT
        pat[6] = (byte) 0xB0; pat[7] = 0x0D;              // section_length = 13
        pat[8] = 0x00; pat[9] = 0x01;                     // tsid
        pat[10] = (byte) 0xC1; pat[11] = 0x00; pat[12] = 0x00;
        pat[13] = 0x00; pat[14] = 0x01;                   // program 1
        pat[15] = (byte) 0xE1; pat[16] = 0x00;            // PMT PID 0x100
        pat[17] = 0x00; pat[18] = 0x00; pat[19] = 0x00; pat[20] = 0x00; // CRC

        byte[] pmt = new byte[188];
        pmt[0] = 0x47; pmt[1] = 0x41; pmt[2] = 0x00; pmt[3] = 0x10;  // PUSI,PID0x100
        pmt[4] = 0x00;                                    // pointer_field
        pmt[5] = 0x02;                                    // table_id = PMT
        pmt[6] = (byte) 0xB0; pmt[7] = 0x17;              // section_length = 23
        pmt[8] = 0x00; pmt[9] = 0x01;                     // program 1
        pmt[10] = (byte) 0xC1; pmt[11] = 0x00; pmt[12] = 0x00;
        pmt[13] = 0x00; pmt[14] = 0x00;                   // PCR PID = none
        pmt[15] = (byte) 0xF0; pmt[16] = 0x00;            // program_info_length = 0
        pmt[17] = 0x1B;                                   // H.264
        pmt[18] = (byte) 0xE1; pmt[19] = 0x01;            // PID 0x101
        pmt[20] = (byte) 0xF0; pmt[21] = 0x00;            // ES_info_len 0
        pmt[22] = 0x0F;                                   // AAC
        pmt[23] = (byte) 0xE1; pmt[24] = 0x02;            // PID 0x102
        pmt[25] = (byte) 0xF0; pmt[26] = 0x00;            // ES_info_len 0

        byte[] out = new byte[188 * 2];
        System.arraycopy(pat, 0, out, 0, 188);
        System.arraycopy(pmt, 0, out, 188, 188);
        return out;
    }
}
