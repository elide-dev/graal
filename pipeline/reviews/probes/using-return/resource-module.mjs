await using outer = {async [Symbol.asyncDispose]() {moduleDisposals.push('outer-a'); await null; await null; moduleDisposals.push('outer-b');}};
{await using inner = {async [Symbol.asyncDispose]() {moduleDisposals.push('inner-a'); await null; moduleDisposals.push('inner-b');}};}
export const answer = 42;
99;
