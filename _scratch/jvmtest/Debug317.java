import java.lang.reflect.Field;

public class Debug317 {
    public static void main(String[] args) throws Exception {
        byte[] ts = Test317.buildTs();
        System.out.println("ts bytes: " + ts.length);
        Class<?> tp = Class.forName("com.your.iptv.util.TsProbe");
        Object p = tp.getDeclaredConstructor().newInstance();

            java.lang.reflect.Method feed = tp.getMethod("feed", byte[].class, int.class);
        feed.invoke(p, ts, ts.length);
        for (String f : new String[]{"currentPmtPid", "videoPid", "audioPid", "pmtDefined",
                "hasVideo", "hasAudio", "videoInitDispatched"}) {
            try {
                Field fl = tp.getDeclaredField(f);
                fl.setAccessible(true);
                System.out.println(f + " = " + fl.get(p));
            } catch (NoSuchFieldException e) {
                System.out.println(f + " = (无此字段)");
            }
        }
        Field pidSt = tp.getDeclaredField("pidStreamType");
        pidSt.setAccessible(true);
        System.out.println("pidStreamType = " + pidSt.get(p));
        java.lang.reflect.Method gm = tp.getMethod("meta");
        Object meta = gm.invoke(p);
        for (Field fl : meta.getClass().getFields()) {
            System.out.println("meta." + fl.getName() + " = " + fl.get(meta));
        }
    }
}
