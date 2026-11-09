export const events=[];
using a={[Symbol.dispose](){events.push('dispose');}};
export const value=43;
events.push('body');
