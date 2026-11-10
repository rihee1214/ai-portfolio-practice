// Only lifetime notifications travel here; credentials and private order data never do.
let generation=0
const listeners=new Set<()=>void>()
export const guestAccessGeneration=()=>generation
export const onGuestAccessChange=(listener:()=>void)=>{listeners.add(listener);return()=>{listeners.delete(listener)}}
export function notifyGuestAccessChange(){generation++;listeners.forEach(listener=>listener())}
