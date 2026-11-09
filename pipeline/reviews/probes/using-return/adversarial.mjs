
let checks=0; const eq=(a,b,label)=>{checks++;if(!Object.is(a,b))throw Error(label+': '+a+' != '+b)};
let order=[]; const sync=(n,e)=>({[Symbol.dispose](){order.push(n);if(e)throw e}});
const asyncR=(n,e)=>({async [Symbol.asyncDispose](){order.push(n+'a');await null;await null;order.push(n+'b');if(e)throw e}});
for (const source of ['{using r=null; 73;}','17; {using r=null;}','{using r=null; try {73;} finally {99;}}']) eq(eval(source),source.startsWith('17')?17:73,'eval completion');
const val={};
async function nested(){await using a=asyncR('a');{await using b=asyncR('b');await null;{using c=sync('c'); return val;}}}
eq(await nested(),val,'nested return');eq(order.join(','),'c,ba,bb,aa,ab','nested order');
order=[];
outer: for(let i=0;i<3;i++){await using a=asyncR('o'+i); for(let j=0;j<2;j++){await using b=asyncR('i'+j);if(i===0)continue outer;break outer;}}
eq(order.join(','),'i0a,i0b,o0a,o0b,i0a,i0b,o1a,o1b','label abrupt order');
const bErr={},e1={},e2={};
for(const async of [false,true]){
 let caught;try{if(async){await using a=asyncR('e1',e1);await using b=asyncR('e2',e2);throw bErr;}else{using a=sync('e1',e1);using b=sync('e2',e2);throw bErr;}}catch(e){caught=e}
 eq(caught instanceof SuppressedError,true,'outer suppressed');eq(caught.error,e1,'last disposer wins');eq(caught.suppressed.error,e2,'first disposer');eq(caught.suppressed.suppressed,bErr,'body suppressed');
}
async function* gen(){await using a=asyncR('g');yield 'yielded';return val;}
for(const action of ['next','return','throw']){let g=gen();eq((await g.next()).value,'yielded','gen first');let got;try{got=await g[action](val)}catch(e){eq(action,'throw','only throw rejects');eq(e,val,'throw preserved');continue}eq(got.value,val,'gen value');eq(got.done,true,'gen done')}
async function* ordinary(){try{return val}finally{yield 1;await null;yield 2;}}
let g=ordinary();eq((await g.next()).value,1,'finally yield1');eq((await g.next()).value,2,'finally yield2');eq((await g.next()).value,val,'finally return');
async function override(){try{await using a=asyncR('override');return val}finally{await null;return 123}}
eq(await override(),123,'finally override');
async function suppressedReturn(){await using a=asyncR('return-error',e1);return val}try{await suppressedReturn();throw Error('missing error')}catch(e){eq(e,e1,'disposer overrides return')}
class Base{};class Derived extends Base{constructor(){using a=sync('ctor');super();}}eq(new Derived() instanceof Derived,true,'derived ctor');
globalThis.moduleDisposals=[];
const mod=await import('./resource-module.mjs');eq(mod.answer,42,'module export');eq(moduleDisposals.join(','),'inner-a,inner-b,outer-a,outer-b','module await disposal');
print('PASS adversarial checks='+checks);
