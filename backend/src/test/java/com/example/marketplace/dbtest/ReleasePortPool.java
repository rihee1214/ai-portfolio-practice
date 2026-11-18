package com.example.marketplace.dbtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Pure closed-interval complement; observed seconds/ports never change deadlines. */
final class ReleasePortPool {
    private static final String POWERSHELL="C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe";
    record Range(int start,int end) {
        Range {if(start<1||end>65535||start>end) {throw new IllegalArgumentException("Invalid observed TCP range");}}
    }
    private final List<Range> intervals;
    private final int size;
    private ReleasePortPool(List<Range> intervals) {
        this.intervals=List.copyOf(intervals);this.size=intervals.stream().mapToInt(range->range.end()-range.start()+1).sum();
        if(size==0) {throw new IllegalArgumentException("No eligible isolated server port outside observed dynamic ranges");}
    }
    static ReleasePortPool fromObservation(JsonNode observation,String expectedId) {
        UUID.fromString(expectedId);
        require(observation!=null&&observation.isObject(),"Missing current port observation");
        require(observation.path("schemaVersion").isIntegralNumber()&&observation.path("schemaVersion").canConvertToInt()&&observation.path("schemaVersion").asInt()==1,"Invalid observation schema");
        require(observation.path("status").asText().equals("passed")&&observation.path("mode").asText().equals("live-current")&&observation.path("observationId").asText().equals(expectedId),"Failed, synthetic or reused port observation");
        var runtime=observation.path("runtime");
        require(isTrue(runtime.path("accepted"))&&runtime.path("version").asText().equals("7.6.9")&&runtime.path("edition").asText().equals("Core")&&
                runtime.path("executable").asText().replace('\\','/').equalsIgnoreCase(POWERSHELL),"Unverified observation runtime");
        var docker=observation.path("dockerHost");
        require(isTrue(docker.path("alreadyRunning"))&&docker.path("pid").isIntegralNumber()&&docker.path("pid").canConvertToInt()&&docker.path("pid").asInt()>0,"Unverified running Docker host");
        String namespace=docker.path("namespace").asText();
        require(namespace.matches("net:\\[\\d+\\]")&&namespace.equals(docker.path("observedNamespace").asText()),"Docker host namespace mismatch");
        return subtract(20000,65535,List.of(range(observation.path("windows").path("ipv4")),range(observation.path("windows").path("ipv6")),range(docker)));
    }
    static ReleasePortPool subtract(int start,int end,List<Range> exclusions) {
        new Range(start,end);require(exclusions!=null,"Missing excluded ranges");
        var sorted=new ArrayList<>(exclusions);require(sorted.stream().allMatch(range->range!=null),"Missing excluded range");sorted.sort(Comparator.comparingInt(Range::start));
        var eligible=new ArrayList<Range>();int next=start;
        for(var excluded:sorted) {
            if(excluded.end()<next) {continue;}
            if(excluded.start()>end) {break;}
            if(excluded.start()>next) {eligible.add(new Range(next,Math.min(end,excluded.start()-1)));}
            next=Math.max(next,excluded.end()+1);
            if(next>end) {break;}
        }
        if(next<=end) {eligible.add(new Range(next,end));}
        return new ReleasePortPool(eligible);
    }
    List<Range> intervals() {return intervals;}
    int size() {return size;}
    int portAt(int index) {
        if(index<0||index>=size) {throw new IndexOutOfBoundsException("Port pool index");}
        int remaining=index;
        for(var range:intervals) {int length=range.end()-range.start()+1;if(remaining<length) {return range.start()+remaining;}remaining-=length;}
        throw new IllegalStateException("Port pool inventory mismatch");
    }
    private static Range range(JsonNode value) {
        var start=value.path("start");var end=value.path("end");
        require(start.isIntegralNumber()&&end.isIntegralNumber()&&start.canConvertToInt()&&end.canConvertToInt()&&start.asInt()>=1&&end.asInt()<=65535&&start.asInt()<=end.asInt(),"Missing or invalid numeric TCP range");
        return new Range(start.asInt(),end.asInt());
    }
    private static boolean isTrue(JsonNode value) {return value.isBoolean()&&value.asBoolean();}
    private static void require(boolean condition,String message) {if(!condition) {throw new IllegalArgumentException(message);}}
}
