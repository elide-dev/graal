import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import java.util.*;

public class ContinuationProbe {
    static final ContinuationScope S = new ContinuationScope("probe");
    static final int DEPTH = 96;
    static volatile int phase;
    static void deep(int n) {
        if (n > 0) deep(n - 1);
        else { phase = 1; Continuation.yield(S); }
    }
    public static void main(String[] args) {
        String mode = args.length == 0 ? "replace" : args[0];
        Continuation c = new Continuation(S, () -> { deep(DEPTH); phase = 2; Continuation.yield(S); phase = 3; });
        c.run();
        List<String> frames = new ArrayList<>();
        Throwable[] resumeFailure = {null};
        c.stackWalker().forEach(f -> {
            if (frames.isEmpty()) {
                try {
                    if (!mode.equals("gc")) c.run();
                    if (mode.equals("complete")) c.run();
                    System.out.println("after resume phase=" + phase + " done=" + c.isDone());
                } catch (Throwable e) { resumeFailure[0] = e; System.out.println("RESUME_FAILED " + e); }
            }
            if (mode.equals("gc") || mode.equals("replacegc") || mode.equals("complete")) {
                byte[][] pressure = new byte[16][];
                for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[65536];
                System.gc();
            }
            frames.add(f.getMethodName());
        });
        long count = frames.stream().filter("deep"::equals).count();
        System.out.println("RESULT mode=" + mode + " deep=" + count + " total=" + frames.size() + " resumeFailure=" + resumeFailure[0]);
        if (count != DEPTH + 1) throw new AssertionError(frames.toString());
        while (!c.isDone()) c.run();
    }
}
