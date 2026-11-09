from pathlib import Path
import zipfile
p=Path(__file__).parent
with zipfile.ZipFile(p/'provider.jar','w') as provider, zipfile.ZipFile(p/'probe.jar','w') as app:
    for f in sorted((p/'classes').rglob('*.class')):
        rel=f.relative_to(p/'classes').as_posix()
        (provider if 'G1ReviewOptions' in rel else app).write(f,rel)
    provider.writestr('META-INF/services/jdk.graal.compiler.options.OptionDescriptors','review.G1ReviewOptions_OptionDescriptors\n')
