# A fresh Windows kernel job owns one worker and its entire descendant hierarchy.
# The owner's noninheritable handle stays strongly rooted until process exit.
if(-not ('MarketplaceVerification.WorkerJob' -as [type])){
Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using Microsoft.Win32.SafeHandles;
namespace MarketplaceVerification {
 public sealed class JobSnapshot { public bool Exists {get;set;} public long[] ProcessIds {get;set;} }
 public sealed class JobTermination { public JobSnapshot Before {get;set;} public JobSnapshot After {get;set;} public bool Requested {get;set;} }
 public static class WorkerJob {
  private static SafeFileHandle owner;
  [StructLayout(LayoutKind.Sequential)] private struct BasicLimits { public long ProcessTime;public long JobTime;public uint Flags;public UIntPtr MinWorkingSet;public UIntPtr MaxWorkingSet;public uint ActiveLimit;public UIntPtr Affinity;public uint Priority;public uint Scheduling; }
  [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong ReadOps;public ulong WriteOps;public ulong OtherOps;public ulong ReadBytes;public ulong WriteBytes;public ulong OtherBytes; }
  [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits { public BasicLimits Basic;public IoCounters Io;public UIntPtr ProcessMemory;public UIntPtr JobMemory;public UIntPtr PeakProcessMemory;public UIntPtr PeakJobMemory; }
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] private static extern SafeFileHandle CreateJobObject(IntPtr attributes,string name);
  [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] private static extern SafeFileHandle OpenJobObject(uint access,bool inherit,string name);
  [DllImport("kernel32.dll",SetLastError=true)] private static extern bool SetInformationJobObject(SafeFileHandle job,int information,IntPtr buffer,uint length);
  [DllImport("kernel32.dll",SetLastError=true)] private static extern bool AssignProcessToJobObject(SafeFileHandle job,IntPtr process);
  [DllImport("kernel32.dll",SetLastError=true)] private static extern bool QueryInformationJobObject(SafeFileHandle job,int information,IntPtr buffer,uint length,out uint returned);
  [DllImport("kernel32.dll",SetLastError=true)] private static extern bool TerminateJobObject(SafeFileHandle job,uint code);
  private static void Name(string value) { const string prefix="Local\\marketplace-verify-";Guid parsed;if(value==null||!value.StartsWith(prefix,StringComparison.Ordinal)||!Guid.TryParseExact(value.Substring(prefix.Length),"N",out parsed))throw new ArgumentException("Fresh verification UUID job name required"); }
  public static void AttachSelf(string name) {
   Name(name);if(owner!=null)throw new InvalidOperationException("Worker already owns a job");
   var job=CreateJobObject(IntPtr.Zero,name);int created=Marshal.GetLastWin32Error();
   if(job.IsInvalid){job.Dispose();throw new Win32Exception(created,"Create worker job failed");}
   if(created==183){job.Dispose();throw new InvalidOperationException("Worker job already exists; refusing foreign ownership");}
   try {
    var limits=new ExtendedLimits();limits.Basic.Flags=0x2000; // KILL_ON_JOB_CLOSE, no breakaway flags.
    int size=Marshal.SizeOf<ExtendedLimits>();IntPtr buffer=Marshal.AllocHGlobal(size);
    try {Marshal.StructureToPtr(limits,buffer,false);if(!SetInformationJobObject(job,9,buffer,(uint)size))throw new Win32Exception(Marshal.GetLastWin32Error(),"Set worker job limits failed");}finally{Marshal.FreeHGlobal(buffer);}
    using(var process=Process.GetCurrentProcess()){if(!AssignProcessToJobObject(job,process.Handle))throw new Win32Exception(Marshal.GetLastWin32Error(),"Attach worker to nested job failed");}
    owner=job; // Never dispose this handle while the worker is writing terminal evidence.
   }catch{job.Dispose();throw;}
  }
  public static SafeFileHandle OpenForQuery(string name) {
   Name(name);var handle=OpenJobObject(4,false,name);if(!handle.IsInvalid)return handle;int code=Marshal.GetLastWin32Error();handle.Dispose();if(code==2)return null;throw new Win32Exception(code,"Open owned worker job failed");
  }
  private static JobSnapshot Members(SafeFileHandle job) {
   for(int size=512;size<=1048576;size*=2){IntPtr buffer=Marshal.AllocHGlobal(size);try{uint returned;if(QueryInformationJobObject(job,3,buffer,(uint)size,out returned)){int count=Marshal.ReadInt32(buffer,4);if(count<0||8L+(long)count*IntPtr.Size>size)throw new InvalidOperationException("Invalid worker job membership length");var ids=new long[count];for(int i=0;i<count;i++)ids[i]=Marshal.ReadIntPtr(buffer,8+i*IntPtr.Size).ToInt64();return new JobSnapshot{Exists=true,ProcessIds=ids};}int code=Marshal.GetLastWin32Error();if(code!=234)throw new Win32Exception(code,"Query worker job membership failed");}finally{Marshal.FreeHGlobal(buffer);}}
   throw new InvalidOperationException("Worker job membership exceeds finite observation buffer");
  }
  public static JobSnapshot Snapshot(string name) { using(var job=OpenForQuery(name)){return job==null?new JobSnapshot{Exists=false,ProcessIds=Array.Empty<long>()}:Members(job);} }
  public static JobTermination Terminate(string name) {
   Name(name);var job=OpenJobObject(12,false,name);
   if(job.IsInvalid){int code=Marshal.GetLastWin32Error();job.Dispose();if(code==2){var absent=new JobSnapshot{Exists=false,ProcessIds=Array.Empty<long>()};return new JobTermination{Before=absent,After=absent,Requested=false};}throw new Win32Exception(code,"Open job for owned termination failed");}
   using(job){var before=Members(job);bool requested=before.ProcessIds.Length>0;if(requested&&!TerminateJobObject(job,1))throw new Win32Exception(Marshal.GetLastWin32Error(),"Owned job termination failed");var timer=Stopwatch.StartNew();JobSnapshot after;do{after=Members(job);if(after.ProcessIds.Length==0)break;Thread.Sleep(20);}while(timer.ElapsedMilliseconds<2000);return new JobTermination{Before=before,After=after,Requested=requested};}
  }
 }
}
'@ -ErrorAction Stop
}

function Stop-VerificationWorkerJob([string]$Name){
    $value=[MarketplaceVerification.WorkerJob]::Terminate($Name)
    @{status=if($value.After.ProcessIds.Count -eq 0){'cleaned'}else{'unresolved'};jobName=$Name;terminationRequested=$value.Requested;processIdsBefore=@($value.Before.ProcessIds);processIdsAfter=@($value.After.ProcessIds)}
}
