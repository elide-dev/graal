package debugquality;

import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.function.CEntryPoint;

import com.oracle.svm.shared.AlwaysInline;
import com.oracle.svm.shared.NeverInline;

public class DebugQualityProbe {
    public static class DataOnly {
        public volatile int value;
        public volatile long other;
    }

    private static final DataOnly DATA = new DataOnly();

    @AlwaysInline("Exercise an inline scope with a live argument")
    static int inner(int value) {
        int shifted = value + 3;
        return shifted * 2; // DEBUG_QUALITY_INNER
    }

    @NeverInline("Keep a concrete caller for inline attribution")
    static int outer(int value) {
        return inner(value);
    }

    @CEntryPoint(name = "debug_quality")
    public static int entry(IsolateThread thread, int value) {
        return outer(value);
    }

    public static void main(String[] args) {
        int result = outer(args.length + 17);
        DataOnly local = DATA;
        local.value = result;
        local.other = result * 2L;
        System.out.println(local.value + ":" + local.other);
    }
}
