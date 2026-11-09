import java.net.*;
import java.util.*;
import java.lang.reflect.*;
public class AuthorityProbe {
 static class H extends URLStreamHandler {
  int lookups;
  protected URLConnection openConnection(URL u) { throw new UnsupportedOperationException("analogue only"); }
  protected InetAddress getHostAddress(URL u) { lookups++; return null; }
 }
 static URL url(H h,String auth) throws Exception { return new URL(null,"resource://"+auth+"/1!/x",h); }
 static void pair(H h,String a,String b,boolean expected) throws Exception {
  URL x=url(h,a),y=url(h,b); boolean eq=x.equals(y); boolean sf=x.sameFile(y);
  int hx=x.hashCode(),hy=y.hashCode(); Set<URL> set=new HashSet<>(List.of(x,y));
  System.out.println(a+" vs "+b+": equals="+eq+", sameFile="+sf+", hashes="+hx+","+hy+", set="+set.size());
  if(eq!=expected||sf!=expected||(eq&&hx!=hy)||set.size()!=(expected?1:2))throw new AssertionError(a);
  int calls=h.lookups; if(x.hashCode()!=hx||calls!=h.lookups)throw new AssertionError("hash caching");
 }
 public static void main(String[] args)throws Exception {
  H h=new H();
  pair(h,"127.0.0.1","127.1",false);
  pair(h,"127.0.0.1","2130706433",false);
  pair(h,"[::1]","[0:0:0:0:0:0:0:1]",false);
  pair(h,"APP","app",true);
  pair(h,"app","app",true);
  pair(h,"moduleA@app","moduleB@app",true);
  URL x=url(h,"127.0.0.1"),y=url(h,"127.1");
  Field f=URL.class.getDeclaredField("hostAddress");f.setAccessible(true);
  f.set(x,InetAddress.getByAddress(new byte[]{127,0,0,1}));f.set(y,InetAddress.getByAddress(new byte[]{127,0,0,1}));
  if(x.equals(y)||x.sameFile(y)||new HashSet<>(List.of(x,y)).size()!=2)throw new AssertionError("cached address");
  System.out.println("cached address ignored; repeated hashes cached; analogue assertions passed");
 }
}
