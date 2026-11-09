package review;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.shared.option.APIOption;
import jdk.graal.compiler.options.Option;
public class G1ReviewOptions {
 @APIOption(name="G1", group=SubstrateOptions.GCGroup.class, customHelp="Public native G1 review scaffold")
 @Option(help="Register public G1 for an isolated review image")
 public static final HostedOptionKey<Boolean> ReviewG1 = new HostedOptionKey<>(false);
}
