import io.github.lizongying.Gua;
import java.nio.file.*;
public class GuaTool {
  public static void main(String[] a) throws Exception {
    String txt = Files.readString(Paths.get(a[1]));
    Gua g = new Gua();
    String out;
    if ("decode".equals(a[0])) {
      out = g.verify(txt) ? g.decode(txt) : "VERIFY_FAILED";
    } else {
      out = g.encode(txt);
    }
    Files.writeString(Paths.get(a[2]), out);
    System.out.println("ok len=" + out.length());
  }
}
