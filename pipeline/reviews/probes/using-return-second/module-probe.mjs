import {events,value} from './module-resource.mjs';
import {events as syncEvents,value as syncValue} from './module-sync.mjs';
if(value!==42 || events.join(',')!=='body,second-start,second-end,first-start,first-end') throw Error('async module completion '+events);
if(syncValue!==43||syncEvents.join(',')!=='body,dispose')throw Error('sync module completion '+syncEvents);
let failed=false;
try { await import('./module-error.mjs'); } catch(e) { if(!(e instanceof SuppressedError)||e.error!=='dispose'||e.suppressed!=='body')throw e;failed=true; }
if(!failed)throw Error('missing module error');
print('PASS module sync/async ordering, exports, and suppression');
