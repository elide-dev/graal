package review;

import java.nio.file.*;
import java.util.*;
import jdk.jfr.*;
import jdk.jfr.consumer.*;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.test.jfr.TestNativeGCAllocationSampleEvent;

public final class JfrNativeReviewMain {
    static volatile Object sink;
    static volatile int impossibleLength = Integer.MAX_VALUE;
    static volatile int negativeLength = -1;
    static final String EVENT = "jdk.ObjectAllocationSample";
    static final class WeightPayload {}
    static final class FailedPayload {}
    static final class NegativePayload {}
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        require(org.graalvm.nativeimage.ImageInfo.inImageRuntimeCode(), "must execute as native image");
        require(com.oracle.svm.core.SubstrateOptions.useG1GC(), "must execute native G1");
        require(com.oracle.svm.core.jfr.HasJfrSupport.get(), "must include JFR");
        System.out.println("NATIVE_G1_JFR=true");
        if (args.length > 0 && args[0].equals("virtual")) { virtualExactTest(); return; }
        TestNativeGCAllocationSampleEvent.checkForJFR();
        new TestNativeGCAllocationSampleEvent().instanceAndArraySamplesHaveTypesWeightsAndStacks();
        System.out.println("EXACT_CANDIDATE_TEST=PASS");
        new TestNativeGCAllocationSampleEvent().instanceAndArraySamplesHaveTypesWeightsAndStacks();
        System.out.println("RESTART_EXACT_TEST=PASS");
        List<RecordedEvent> events = record(true, true, false);
        List<RecordedEvent> weighted = events.stream().filter(e -> e.getEventType().getName().equals(EVENT) && e.getClass("objectClass").getName().equals(WeightPayload[].class.getName())).toList();
        require(weighted.size() == 32, "large allocation sample count=" + weighted.size());
        long sum = 0;
        for (RecordedEvent e : weighted) {
            require(e.getThread().getJavaThreadId() == Thread.currentThread().threadId(), "event thread");
            require(e.getLong("weight") > 0, "positive weight");
            require(e.getStackTrace() != null && e.getStackTrace().getFrames().stream().anyMatch(f -> f.getMethod().getName().equals("allocateWeighted")), "weighted allocator stack");
            sum += e.getLong("weight");
        }
        long expectedPayloadBytes = 32L * 524288 * 4;
        require(sum >= expectedPayloadBytes && sum < expectedPayloadBytes * 1.02, "unexpected aggregate weight=" + sum);
        System.out.println("CONTROLLED_WEIGHT=PASS count=" + weighted.size() + " weight=" + sum + " expected_payload=" + expectedPayloadBytes);
        require(events.stream().noneMatch(e -> e.getEventType().getName().equals(EVENT) && (e.getClass("objectClass").getName().equals(FailedPayload[].class.getName()) || e.getClass("objectClass").getName().equals(NegativePayload[].class.getName()))), "failed allocation event");
        System.out.println("FAILED_ARRAY_AND_NEGATIVE_LENGTH=PASS");
        require(record(false, true, false).stream().noneMatch(e -> e.getEventType().getName().equals(EVENT)), "disabled event was emitted");
        System.out.println("DISABLED_EVENT=PASS");
        List<RecordedEvent> noStacks = record(true, false, false).stream().filter(e -> e.getEventType().getName().equals(EVENT)).toList();
        require(!noStacks.isEmpty() && noStacks.stream().allMatch(e -> e.getStackTrace() == null), "stack disabled");
        System.out.println("DISABLED_STACK=PASS events=" + noStacks.size());
        List<RecordedEvent> throttled = record(true, true, true).stream().filter(e -> e.getEventType().getName().equals(EVENT)).toList();
        System.out.println("THROTTLED_OBSERVATION events=" + throttled.size() + " (not a rate bound assertion)");
        virtualAllocations();
        sink = null;
    }
    static void virtualAllocations() throws Exception {
        Path path = Files.createTempFile("jfr-native-virtual-allocation", ".jfr");
        try {
            Thread t;
            Throwable[] failure = new Throwable[1];
            try (Recording r = new Recording()) {
                r.enable(EVENT).with("throttle", "off").withStackTrace();
                r.start();
                t = Thread.ofVirtual().start(() -> { try { allocateWeighted(); } catch (Throwable e) { failure[0] = e; } });
                t.join();
                r.stop(); r.dump(path);
            }
            if (failure[0] != null) throw new AssertionError("virtual allocation workload", failure[0]);
            List<RecordedEvent> samples = RecordingFile.readAllEvents(path).stream().filter(e -> e.getEventType().getName().equals(EVENT) && e.getClass("objectClass").getName().equals(WeightPayload[].class.getName())).toList();
            require(samples.size() == 32, "virtual sample count=" + samples.size());
            for (RecordedEvent e : samples) {
                require(e.getThread().isVirtual() && e.getThread().getJavaThreadId() == t.threadId(), "virtual event thread identity");
                require(e.getLong("weight") > 0, "virtual sample weight");
                require(e.getStackTrace() != null && e.getStackTrace().getFrames().stream().anyMatch(f -> f.getMethod().getName().equals("allocateWeighted")), "virtual allocation stack");
            }
            System.out.println("VIRTUAL_ALLOCATION_SAMPLES=PASS events=" + samples.size() + " thread=" + t.threadId());
        } finally { Files.deleteIfExists(path); }
    }
    static void virtualExactTest() throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = Thread.ofVirtual().start(() -> { try { new TestNativeGCAllocationSampleEvent().instanceAndArraySamplesHaveTypesWeightsAndStacks(); } catch(Throwable e) { failure[0] = e; } });
        t.join();
        if (failure[0] != null) throw new AssertionError("virtual thread exact test", failure[0]);
        System.out.println("VIRTUAL_THREAD_EXACT_TEST=PASS");
        sink = null;
    }
    static List<RecordedEvent> record(boolean enabled, boolean stack, boolean throttle) throws Exception {
        Path path = Files.createTempFile("jfr-native-review", ".jfr");
        try {
            try (Recording r = new Recording()) {
                if (enabled) { EventSettings e = r.enable(EVENT).with("throttle", throttle ? "1/s" : "off"); if (stack) e.withStackTrace(); else e.withoutStackTrace(); }
                else r.disable(EVENT);
                r.start();
                prime();
                allocateWeighted();
                try { sink = new FailedPayload[impossibleLength]; throw new AssertionError("expected OOME"); } catch (OutOfMemoryError expected) {}
                try { sink = new NegativePayload[negativeLength]; throw new AssertionError("expected negative array length"); } catch (NegativeArraySizeException expected) {}
                r.stop(); r.dump(path);
            }
            return RecordingFile.readAllEvents(path);
        } finally { Files.deleteIfExists(path); }
    }
    @NeverInline("Prime last allocation sample separately from weighted allocations")
    static void prime() { sink = new byte[2 * 1024 * 1024]; }
    @NeverInline("Keep allocation caller in serialized stack")
    static void allocateWeighted() { for (int i = 0; i < 32; i++) sink = new WeightPayload[524288]; }
}
