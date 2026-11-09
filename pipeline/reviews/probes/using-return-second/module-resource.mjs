export const events=[];
export const value=42;
await using first = {async [Symbol.asyncDispose](){events.push('first-start');await 0;await 0;events.push('first-end');}};
await using second = {async [Symbol.asyncDispose](){events.push('second-start');await 0;events.push('second-end');}};
events.push('body');
