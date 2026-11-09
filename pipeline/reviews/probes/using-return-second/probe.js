let count = 0;
function eq(a,b,label='') { count++; if (!Object.is(a,b)) throw Error(label+': '+String(a)+' != '+String(b)); }
const error = {}, override = {};
const res = () => ({[Symbol.dispose]() {}});
// Terminal branches, nonterminal scopes, parameter blocks, constructors and eval completion.
for (const flag of [false,true]) {
 function branch(x=flag) { using a=res(); if(x) { using b=res(); return 10; } else { using c=res(); return 20; } }
 eq(branch(),flag?10:20,'branch');
 function scope() { { using a=res(); 1; } return 30; }
 eq(scope(),30);
 function catchBody() { try { throw error; } catch(e) { using a=res(); return e; } }
 eq(catchBody(),error);
}
eq(eval('{ using a=res(); 123; }'),123,'eval');
eq(eval('123; { using a=res(); }'),123,'empty eval');
class B { constructor() { using x=res(); return {b:42}; } }
eq(new B().b,42);
class D extends B { constructor() { using x=res(); super(); } }
eq(new D().b,42);
// Reuse the same ordinary TryFinallyNode after normal completion and two suspensions.
function* repeated() { for(let i=0;i<4;i++) { try { yield i; } finally { yield 'a'; yield 'b'; } } return 99; }
let g=repeated(); for(let i=0;i<4;i++) {eq(g.next().value,i);eq(g.next().value,'a');eq(g.next().value,'b');} eq(g.next().value,99);eq(g.next().done,true);
function* finallyOverride() { try { return 1; } finally { yield 2; yield 3; return 4; } }
g=finallyOverride();eq(g.next().value,2);eq(g.next().value,3);eq(g.next().value,4);
function* pending() { try { throw error; } finally { yield 1; yield 2; } }
g=pending();eq(g.next().value,1);eq(g.next().value,2);try{g.next();throw Error('missing throw');}catch(e){eq(e,error);}
g=pending();eq(g.next().value,1);eq(g.return(7).value,7,'injected return overrides pending throw');
g=pending();g.next();try{g.throw(override);throw Error('missing throw');}catch(e){eq(e,override);}
// Return injected while generator suspended in an ordinary finally nested in using scope.
let disposed=0;
function* usingGen() { using x = {[Symbol.dispose](){disposed++;}}; try { yield 'body'; return 'old'; } finally {yield 'cleanup';} }
g=usingGen();eq(g.next().value,'body');eq(g.return('outer').value,'cleanup');eq(g.next().value,'outer');eq(disposed,1);
async function run() {
 const order=[];
 const ar=(name,err)=>({async [Symbol.asyncDispose](){order.push(name+'1');await 0;order.push(name+'2');await 0;if(err) throw err;}});
 async function repeat(){for(let i=0;i<4;i++){try{await 0;}finally{await 0;await 0;}}return 98;}
 eq(await repeat(),98);
 async function branches(flag) { await using a=ar('a'); if(flag){await using b=ar('b');return false;} return null; }
 eq(await branches(true),false);eq(order.join(','),'b1,b2,a1,a2');order.length=0;eq(await branches(false),null);
 async function* ag(){await using a=ar('g');yield 1;return {ok:1};}
 let agi=ag();eq((await agi.next()).value,1);eq((await agi.next()).value.ok,1);
 agi=ag();await agi.next();eq((await agi.return(77)).value,77);
 agi=ag();await agi.next();try{await agi.throw(error);throw Error('missing throw');}catch(e){eq(e,error);}
 const a={},b={},body={};
 async function errors(){await using first=ar('first',a);await using second=ar('second',b);throw body;}
 try{await errors();throw Error('missing throw');}catch(e){eq(e instanceof SuppressedError,true);eq(e.error,a);eq(e.suppressed.error,b);eq(e.suppressed.suppressed,body);}
 async function overridden(){try{await using a=ar('a',error);return 1;}finally{await 0;return 6;}}
 eq(await overridden(),6);
 async function normalFinally(){try{ return 40;}finally{await 0;await 0;}}
 eq(await normalFinally(),40);
 async function throwingFinally(){try{ return 40;}finally{await 0;throw override;}}
 try{await throwingFinally();throw Error('missing throw');}catch(e){eq(e,override);}
 const loops=[];
 outer:for(let i=0;i<3;i++){await using a={async [Symbol.asyncDispose](){await 0;loops.push(i);}};if(i===0)continue outer;break outer;}
 eq(loops.join(','),'0,1');
 print('PASS '+count+' checks');
}
run();
