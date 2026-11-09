from pathlib import Path
import json,hashlib,subprocess,shutil
scratch=Path('/tmp/superclass-second.PL9WSd')
root=Path('/build/caches/graal-pipeline/next-upstream-batch')
art=root/'reviews/superclass-second-artifacts'
jdk='/home/sam/.mx/jdks/labsjdk-ce-latest-jvmci-25.6-b27_amd64'
launcher='/build/caches/mx/graal-next-superclass/sdk/linux-amd64/GRAALVM_9AF3634DB2_JAVA25/graalvm-9af3634db2-java25-25.6.0-dev/lib/svm/bin/native-image'
wt='/build/worktrees/graal/upstream-next-superclass-flags'
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
for p in scratch.iterdir():
 if p.is_file() and p.suffix in ('.java','.log','.txt','.py'):shutil.copy2(p,art/p.name)
shutil.copytree(scratch/'probe',art/'probe',dirs_exist_ok=True)
files=list(art.rglob('*'))+list(root.glob('superclass-*.log'))
files += [Path('/build/caches/mx/graal-next-superclass/substratevm/dists/svm.jar'),Path('/build/caches/mx/graal-next-superclass/substratevm/svmbuild/linux-amd64/junit/svmjunit'),Path(jdk+'/lib/src.zip')]
if (scratch/'superclass-probe').exists():files.append(scratch/'superclass-probe')
cmd=lambda s,rc,result:{'command':s,'exit_code':rc,'result':result}
base=f'JAVA_HOME={jdk} {launcher} -J-XX:ActiveProcessorCount=8 -J-Xmx8g --parallelism=8 -H:+UnlockExperimentalVMOptions -H:+RuntimeClassLoading -cp {scratch}'
report={
'review':'superclass-second','invocation':'6/12','requested_model_alias':'gpt-6-astra','requested_reasoning_effort':'high','actual_backend':None,'actual_model_version':None,'cost':None,'tokens':None,'model_disclosure':'Requested alias recorded as instructed; actual backend/version/cost/token accounting unavailable.',
'candidate':{'commit':'e3d79d3595992718296ad201cea273ccbb3468d8','subject':'Check class-file finality when loading subclasses'},
'baseline':{'commit':'a369308fb9f4fbed7746caf33c87eb8f3a61b613','subject':'[GR-81123] Preserve pending initialization writes across control-flow merges'},
'worktree':wt,'tracked_source_edits':False,'independence':'Specification, exact diff, source paths, and local JDK JVMCI implementation inspected before implementer logs. No other reviewer findings consulted; no agents spawned.',
'specification':[{'url':'https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-5.html#jvms-5.3.5','assessment':'Derivation must reject a final direct superclass with IncompatibleClassChangeError. Class-file finality controls this; nested reflective metadata does not redefine class-file finality.'}],
'source_assessment':[
'CremaResolvedObjectType constructor takes ParserKlass flags masked by JVM_RECOGNIZED_CLASS_MODIFIERS, preserving ACC_FINAL.',
'BuildTimeInterpreterUniverse.createResolvedObjectType uses OriginalClassProvider.getOriginalType(...).getModifiers(); local HotSpotResolvedObjectTypeImpl.getModifiers reads Klass access flags for nonarrays, rather than Class.getModifiers. Explicit JVMCI test independently confirms both mismatch directions.',
'DynamicHubInitializer.initialize metadata installs interpreter types when runtime class loading is supported. CremaSupportImpl.createHub installs runtime interpreter types before publication and emits a store-store barrier.',
'Null metadata falls back to reflective flags, retaining previous behavior and avoiding a null dereference. This is not a general raw-flags solution for metadata-less conflicting nested classes. No supported nonprimitive-superclass path lacking metadata was identified; subsequent verifySuperAccesses also obtains interpreter types.',
'No changes to access, sealing, interface checks, class initialization, or exception type/message. No new allocation in finality helper.',
'Committed tests directly exercise class derivation; constructors/instantiation are unnecessary. They cover mismatches in both directions and nonfinal control. Independent probe expands coverage to matching final, no InnerClasses, cross-loader delegation and AOT parents.'
],
'findings':[],
'commands':[
cmd('git diff a369308fb9f4fbed7746caf33c87eb8f3a61b613 e3d79d3595992718296ad201cea273ccbb3468d8',0,'13-line production helper change and new 85-line test file inspected'),
cmd(f'{jdk}/bin/javac -d {scratch} {scratch}/Generate.java',1,'Initial setup failed with two unresolved Probe references; original source preserved as Generate.failed.java. Follow-on generator/Probe launches also failed. See failed-attempts.txt.'),
cmd(f"sed -i 's/Probe.class/Generate.class/g' {scratch}/Generate.java",0,'Corrected only scratch helper'),
cmd(f'{jdk}/bin/javac -d {scratch} {scratch}/Generate.java && {jdk}/bin/java -cp {scratch} Generate generate {scratch} && {jdk}/bin/javac -cp {scratch} -d {scratch} {scratch}/Probe.java && {jdk}/bin/java -cp {scratch} Probe',0,'PASS 18 superclass cases; jvm.log'),
cmd(f'{jdk}/bin/javac --add-modules jdk.internal.vm.ci --add-exports jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED -cp {scratch} -d {scratch} {scratch}/JvmciProbe.java',0,'Compiled JVMCI probe'),
cmd(f'{jdk}/bin/java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI --add-modules jdk.internal.vm.ci --add-exports jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED --add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED -cp {scratch} JvmciProbe',0,'Four fixtures matched raw class-file finality; jvmci.log'),
cmd('/build/caches/mx/graal-next-superclass/substratevm/svmbuild/linux-amd64/junit/svmjunit --verbose',0,'3/3 passed, no skips; svmjunit-rerun.log'),
cmd(base+f' -o {scratch}/probe Probe',1,'Reached image creation; failed because output path collided with probe package directory. native-build.log retained.'),
cmd(base+f' -o {scratch}/superclass-probe Probe',0,'Standalone native image built; native-build-retry.log'),
cmd(f'{scratch}/superclass-probe',0,'PASS 18 superclass cases; native-run.log'),
cmd('git status --porcelain',0,'Clean worktree')],
'implementer_logs':{'baseline':'Two intended failures: truly final parent accepted when reflective flags nonfinal; raw nonfinal parent rejected when reflective flags final. Third test passed.','candidate':'3/3 passed','checkstyle':'Completed log contains no violations. Independently inspected, not rerun.','earlier_failures':'Initial build log and retry retained; retry failed on obsolete DynamicHub.getInterpreterType API. Adapted candidate uses DynamicHubUtils.getInterpreterType and subsequent build succeeded.'},
'artifacts':[{'path':str(p),'sha256':sha(p)} for p in files if p.is_file()],
'limits':['Linux amd64 and supplied JDK only; no full suite, Kotlin compiler integration, shared-library/libjvm, Ristretto-specific, layered-image, or cross-platform run.','Null-interpreter-metadata branch was source-reviewed, not forcibly exercised by corrupting metadata.','Standalone probe checks class derivation, not instance execution; appropriate to this finality gate.','No independent baseline image rebuilt; baseline regression evidence comes from supplied logs.','Native probe binary hash recorded before disposable scratch cleanup; source, class fixtures and logs archived.'],
'verdict':'approve: no actionable defect identified for exact candidate'
}
(root/'reviews/superclass-second.json').write_text(json.dumps(report,indent=2)+'\n')
