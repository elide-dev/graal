import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;
import java.util.*;

public class Counterexamples {
  static final ContinuationScope S = new ContinuationScope("review");
  static int epoch;
  static volatile Object garbage;
  static void deep(int n) { if(n != 0) deep(n-1); else Continuation.yield(S); }
  static void require(boolean v, String message) { if(!v) throw new AssertionError(message); }
  static List<String> frames(Continuation c) {return c.stackWalker().walk(s -> s.map(StackWalker.StackFrame::getMethodName).toList());}
  static void test(int mode) {
    epoch = 0;
    Continuation c = new Continuation(S, () -> { deep(48); epoch=1; deep(2); epoch=2; });
    require(frames(c).isEmpty(), "unstarted continuation nonempty");
    c.run();
    List<String> before=frames(c), seen=new ArrayList<>();
    c.stackWalker().forEach(f -> {
      if(seen.isEmpty()) {
        c.run();
        require(epoch==1, "did not reach second yield");
        require(frames(c).stream().filter("deep"::equals).count()==3, "fresh walk did not see replacement snapshot");
        if(mode==1) { c.run(); require(c.isDone(), "did not complete"); }
      }
      for(int i=0; i<8; i++) garbage=new byte[256*1024];
      System.gc();
      seen.add(f.getMethodName());
    });
    require(seen.equals(before), "snapshot changed mode="+mode+" expected="+before+" got="+seen);
    if(!c.isDone()) c.run();
    require(frames(c).isEmpty(), "completed continuation nonempty");
    System.out.println("PASS mode="+mode+" frames="+seen.size());
  }
  public static void main(String[] args) {for(int i=0;i<3;i++){test(0);test(1);} System.out.println("PASS six walks: replacement and completion, nested fresh walkers, allocation and explicit GC at every frame");}
}
