from pathlib import Path
import re,json,gzip,sys,subprocess,hashlib
from elftools.elf.elffile import ELFFile
def check_verifier_completion(status, output):
    assert status in (0, 1), f"Verifier terminated abnormally: {status}"
    expected = "No errors." if status == 0 else "Errors detected."
    assert output.rstrip().endswith(expected), "Verifier did not emit its complete final result"

def error_blocks(output):
    # Progress and warnings can include their own DIE dumps. They terminate the
    # preceding diagnostic, but must not hide any later error record.
    for block in re.split(r'(?m)(?=^(?:error:|warning:|Verifying ))', output):
        if block.startswith('error:'):
            yield block

image=Path(sys.argv[1]).resolve()
peer=Path(sys.argv[2]).resolve()
verifier=Path(sys.argv[3]).resolve()
out=Path(sys.argv[4]).resolve()
out.mkdir(parents=True,exist_ok=True)
with gzip.open(out/'verifier.txt.gz','wt') as log:
 process=subprocess.Popen([str(verifier),'--verify',str(peer)],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
 for line in process.stdout:log.write(line)
 verifier_exit=process.wait()
sha=lambda path:hashlib.file_digest(path.open('rb'),'sha256').hexdigest()

def ranges(part):
 pairs=[(int(a,16),int(b,16)) for a,b in re.findall(r'\[0x([0-9a-f]+), 0x([0-9a-f]+)\)',part)]
 lo=re.search(r'DW_AT_low_pc[^\n]*?(?:\(0x|address = 0x)([0-9a-f]+)\)',part);hi=re.search(r'DW_AT_high_pc \[([^]]+)\][^\n]*\((0x[0-9a-f]+|[0-9]+)\)',part)
 if lo and hi:
  a=int(lo[1],16);b=int(hi[2],0);pairs.append((a,b if hi[1]=='DW_FORM_addr' else a+b))
 return pairs
for kind in ['product']:
 with image.open('rb') as f:
  elf=ELFFile(f);loaded=[(s.name,s['sh_addr'],s['sh_addr']+s['sh_size']) for s in elf.iter_sections() if s['sh_flags']&6==6 and s['sh_size']]
 assert loaded, 'No loaded executable sections were available for range classification'
 with gzip.open(out/'verifier.txt.gz','rt') as f:text=f.read()
 check_verifier_completion(verifier_exit, text)
 checks=[];unknown=[];counts={}
 for block in error_blocks(text):
  category=block.splitlines()[0]
  if category=='error: Aggregated error counts:' or re.fullmatch(r'error: .* occurred [0-9]+ time\(s\)\.',category):continue
  counts[category]=counts.get(category,0)+1
  parts=re.split(r'(?m)(?=^0x[0-9a-f]+:.*DW_TAG_)',block)[1:]
  if category=='error: DIEs have overlapping address ranges:':
   assert len(parts)==2,(category,len(parts))
   sets=[ranges(p) for p in parts];assert all(sets),block[:5000]
   affected=[(max(a,c),min(b,d)) for a,b in sets[0] for c,d in sets[1] if max(a,c)<min(b,d)]
  elif category=="error: DIE address ranges are not contained in its parent's ranges:":
   assert len(parts)==2,(category,len(parts));parent,child=map(ranges,parts);assert child
   affected=[]
   for a,b in child:
    remaining=[(a,b)] if a<b else []
    for c,d in parent:
     next_remaining=[]
     for lo,hi in remaining:
      if d<=lo or hi<=c:next_remaining.append((lo,hi))
      else:
       if lo<c:next_remaining.append((lo,c))
       if d<hi:next_remaining.append((d,hi))
     remaining=next_remaining
    affected+=remaining
  elif category.startswith('error: DIE has overlapping ranges in DW_AT_ranges attribute:'):
   items=ranges(block.splitlines()[0]);assert len(items)==2
   (a,b),(c,d)=items;affected=[(max(a,c),min(b,d))] if max(a,c)<min(b,d) else []
  else:unknown.append(category);continue
  live=[(a,b,name) for a,b in affected for name,c,d in loaded if a<d and c<b]
  checks.append({'category':category,'dieOffsets':[re.match(r'0x[0-9a-f]+',p)[0] for p in parts],'affectedRanges':affected,'liveIntersections':live, 'liveDiagnosticText':block[:5000] if live else None})
 aggregate=sum(map(int,re.findall(r'(?m)^error: .* occurred ([0-9]+) time\(s\)\.',text)))
 assert sum(counts.values())==aggregate, 'Parsed error records do not match the complete LLVM aggregate'
 result={'artifact':str(image),'debugPeer':str(peer),'binarySha256':sha(image),'debugSha256':sha(peer),'verifierSha256':sha(verifier),'verifierExitCode':verifier_exit,'verifierCompleted':True,'loadedExecutableSections':loaded,'counts':counts,'unknown':unknown,'liveDiagnostics':sum(bool(c['liveIntersections']) for c in checks),'scope':'Compute the actual overlapping or uncontained portions for every diagnostic, then intersect each with every loaded executable section; empty/dead ranges retain their diagnostics.','checks':checks}
 with gzip.open(out/'range-classification.json.gz','wt') as f:json.dump(result,f)
 print(kind,'range checks',len(checks),'live',result['liveDiagnostics'],'unknown',len(unknown),flush=True)
 (out/'summary.json').write_text(json.dumps({key:value for key,value in result.items() if key!='checks'},indent=2)+'\n')
 assert not unknown,unknown[:20]
 assert result['liveDiagnostics']==0


assert (verifier_exit == 0) == (not counts), 'Verifier exit status does not match complete diagnostics'
