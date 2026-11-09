import java.net.*;
import java.util.*;
import java.lang.reflect.*;
public class Probe {
 static class H extends URLStreamHandler {
  final boolean fixed; int calls;
  H(boolean fixed) {this.fixed=fixed;}
  protected URLConnection openConnection(URL u) {throw new UnsupportedOperationException();}
  protected InetAddress getHostAddress(URL u) {calls++;return fixed?null:super.getHostAddress(u);}
 }
 static URL url(H h,String s)throws Exception {return new URL(null,"resource://"+s,h);}
 static void pair(String name,String a,String b,boolean expectedFixed,boolean expectedBase)throws Exception {
  for(boolean fixed:new boolean[]{false,true}) {
   H h=new H(fixed); URL x=url(h,a),y=url(h,b);
   boolean eq=x.equals(y),same=x.sameFile(y); int hx=x.hashCode(),hy=y.hashCode();
   Set<URL> set=new HashSet<>();set.add(x);set.add(y);
   System.out.printf("%s fixed=%s equals=%s sameFile=%s hashes=%d/%d set=%d calls=%d%n",name,fixed,eq,same,hx,hy,set.size(),h.calls);
   if(eq!=(fixed?expectedFixed:expectedBase))throw new AssertionError(name);
   if(eq&&hx!=hy)System.out.println("KNOWN INHERITED UNICODE HASH CONTRACT LIMITATION");
   if(x.hashCode()!=hx)throw new AssertionError("unstable cache");
  }
 }
 public static void main(String[]args)throws Exception {
  pair("ipv4-short","127.0.0.1/1!/x","127.1/1!/x",false,true);
  pair("ipv4-integer","127.0.0.1/1!/x","2130706433/1!/x",false,true);
  pair("ipv6-expanded","[::1]/1!/x","[0:0:0:0:0:0:0:1]/1!/x",false,true);
  pair("ipv6-mapped","[::ffff:127.0.0.1]/1!/x","127.0.0.1/1!/x",false,true);
  pair("ipv6-case","[::ABCD]/1!/x","[::abcd]/1!/x",true,true);
  pair("ascii-case","APP.invalid/1!/x","app.invalid/1!/x",true,true);
  Locale.setDefault(Locale.forLanguageTag("tr-TR"));
  pair("turkish-locale-ascii","I.invalid/1!/x","i.invalid/1!/x",true,true);
  pair("unicode-case","\u0131.invalid/1!/x","i.invalid/1!/x",true,true);
  pair("module-userinfo-existing","one@127.0.0.1/1!/x","two@127.0.0.1/1!/x",true,true);
  pair("root","127.0.0.1/1!/x","127.0.0.1/2!/x",false,false);
  H h=new H(true);URL x=url(h,"127.0.0.1/1!/x"),y=url(h,"127.1/1!/x");
  Field f=URL.class.getDeclaredField("hostAddress");f.setAccessible(true);f.set(x,InetAddress.getByName("127.0.0.1"));f.set(y,InetAddress.getByName("127.0.0.1"));
  if(x.equals(y)||x.sameFile(y))throw new AssertionError("cached address leaked");
  System.out.println("seeded equal cached hostAddress ignored: PASS; hashes="+x.hashCode()+"/"+y.hashCode());
  URL frag=url(h,"127.0.0.1/1!/x#fragment");
  if(x.equals(frag)||!x.sameFile(frag))throw new AssertionError("fragment semantics");
  System.out.println("fragment sameFile/equals distinction: PASS");
 }
}
