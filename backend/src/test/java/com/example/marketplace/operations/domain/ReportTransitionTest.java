package com.example.marketplace.operations.domain;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportTransitionTest {
    @Test void everyStateAcceptsOnlyItsNextInvestigationTransition(){
        for(String state:List.of("RECEIVED","INVESTIGATING","CLOSED")){
            for(var action:ReportTransition.values()){
                if("RECEIVED".equals(state) && action==ReportTransition.START){assertEquals("INVESTIGATING",action.apply(state));}
                else if("INVESTIGATING".equals(state) && action==ReportTransition.CLOSE){assertEquals("CLOSED",action.apply(state));}
                else if("CLOSED".equals(state) && action==ReportTransition.REOPEN){assertEquals("INVESTIGATING",action.apply(state));}
                else{assertThrows(IllegalStateException.class,()->action.apply(state));}
            }
        }
    }
}
