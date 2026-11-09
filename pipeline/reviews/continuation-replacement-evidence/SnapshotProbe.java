import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.vm.Continuation;
public class SnapshotProbe {
  static volatile Object garbage;
  static class State { volatile int phase; volatile boolean release; }
  static void park(State s,int p) {s.phase=p; while(s.phase==p && !s.release) LockSupport.park();}
  static void deep(State s,int n,int p) {if(n>0)deep(s,n-1,p);else park(s,p);}
  static void check(boolean ok,String why) {if(!ok)throw new AssertionError(why);}
  static void await(Thread t,State s,int p) {long end=System.nanoTime()+Duration.ofSeconds(15).toNanos(); while(s.phase!=p || t.getState()!=Thread.State.WAITING) {check(System.nanoTime()<end,"await "+p+" state="+t.getState());LockSupport.parkNanos(100_000);}}
  static void finish(Thread t,State s) {s.release=true; LockSupport.unpark(t); try{check(t.join(Duration.ofSeconds(15)),"join");}catch(InterruptedException e){throw new AssertionError(e);}}
  static List<String> frames(Continuation c) {return c.stackWalker().walk(st->st.map(f->f.getClassName()+"."+f.getMethodName()).toList());}
  static void nextPhase(Thread t,State s,int p) {s.phase=0;LockSupport.unpark(t);await(t,s,p);}
  static void test(int mode) throws Exception {
    State s=new State(); Thread t=Thread.ofVirtual().start(()->{deep(s,12,1);deep(s,65,2);park(s,3);});
    try {
      await(t,s,1);
      Field f=Class.forName("java.lang.VirtualThread").getDeclaredField("cont");f.setAccessible(true);
      Continuation c=(Continuation)f.get(t);
      List<String> original=frames(c),a=new ArrayList<>(),b=new ArrayList<>();
      c.stackWalker().walk(outer->c.stackWalker().walk(inner->{
        Iterator<StackWalker.StackFrame> x=outer.iterator(),y=inner.iterator();
        while(x.hasNext()) {
          check(y.hasNext(),"second exhausted");
          StackWalker.StackFrame fx=x.next();a.add(fx.getClassName()+"."+fx.getMethodName());
          // Each walker retains its own physical traversal state across the transition.
          StackWalker.StackFrame fy=y.next();b.add(fy.getClassName()+"."+fy.getMethodName());
          if(a.size()==original.size()/2 && mode!=0) {
            nextPhase(t,s,2);check(frames(c).size()>original.size(),"larger replacement expected");
            if(mode>=2){nextPhase(t,s,3);check(frames(c).size()<original.size(),"smaller replacement expected");}
            if(mode==3)finish(t,s);
          }
          for(int k=0;k<4;k++)garbage=new byte[512*1024];System.gc();
        }
        check(!y.hasNext(),"second extra frames");return null;
      }));
      check(a.equals(original),"first mismatch "+mode+" expected="+original+" actual="+a);
      check(b.equals(original),"second mismatch "+mode);
      System.out.println("PASS mode="+mode+" two walkers originalFrames="+original.size());
    } finally {finish(t,s);}
  }
  public static void main(String[] args)throws Exception{for(int r=0;r<2;r++)for(int m=0;m<4;m++)test(m);System.out.println("PASS 8 cases / 16 interleaved snapshot walks");}
}
