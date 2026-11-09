import jdk.internal.vm.Continuation;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
public class VirtualCounterexamples {
  static volatile int phase;
  static volatile boolean release;
  static volatile Object garbage;
  static void park(int p) { phase=p; while(phase==p && !release) LockSupport.park(); }
  static void deep(int n) { if(n!=0) deep(n-1); else park(1); }
  static void require(boolean b,String m) {if(!b)throw new AssertionError(m);}
  static void await(Thread t,int p) {long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();while(phase!=p||t.getState()!=Thread.State.WAITING){require(System.nanoTime()<end,"park timed out "+phase+" "+t.getState());Thread.yield();}}
  static List<String> frames(Continuation c) {return c.stackWalker().walk(s->s.map(StackWalker.StackFrame::getMethodName).toList());}
  static void test(boolean complete) throws Exception {
    phase=0;release=false;
    Thread t=Thread.ofVirtual().start(()->{deep(40);park(2);park(3);});
    try {
      await(t,1);
      Field field=Class.forName("java.lang.VirtualThread").getDeclaredField("cont");field.setAccessible(true);
      Continuation c=(Continuation)field.get(t);
      List<String> original=frames(c), seen=new ArrayList<>();
      c.stackWalker().forEach(f->{
        if(seen.isEmpty()){
          phase=0;LockSupport.unpark(t);await(t,2);
          require(frames(c).stream().noneMatch(x->x.equals("deep")),"fresh walk retained deep frames");
          phase=0;LockSupport.unpark(t);await(t,3);
          if(complete){release=true;LockSupport.unpark(t);try{require(t.join(Duration.ofSeconds(20)),"completion timed out");}catch(InterruptedException e){throw new AssertionError(e);}}
        }
        for(int k=0;k<8;k++)garbage=new byte[256*1024];System.gc();seen.add(f.getMethodName());
      });
      require(original.equals(seen),"snapshot changed "+complete+" expected "+original+" got "+seen);
      System.out.println("PASS virtual complete="+complete+" frames="+seen.size());
    } finally {release=true;LockSupport.unpark(t);require(t.join(Duration.ofSeconds(20)),"cleanup timeout");}
  }
  public static void main(String[] a)throws Exception{for(int i=0;i<3;i++){test(false);test(true);}System.out.println("PASS six virtual walks: two replacement snapshots, completion, nested fresh walkers and GC at every frame");}
}
