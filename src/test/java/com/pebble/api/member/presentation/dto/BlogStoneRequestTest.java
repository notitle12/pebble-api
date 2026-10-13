package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pebble.api.member.application.BlogStoneService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlogStoneRequestTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private String body(String extra){return "{\"version\":0,\"stones\":[{\"id\":\"s1\",\"postIds\":[\"1\"],\"x\":500,\"y\":300,\"rotation\":0"+extra+"}]}";}
    @Test void oldLayoutsAndSelectedShapesRoundTrip() throws Exception {
        assertNull(BlogStoneRequest.parse(mapper.readTree(body(""))).stones().getFirst().shape());
        for(int shape=0;shape<=5;shape++){
            var stone=BlogStoneRequest.parse(mapper.readTree(body(",\"shape\":"+shape))).stones().getFirst();
            assertEquals(shape,stone.shape());
            assertEquals(stone,mapper.readValue(mapper.writeValueAsString(stone),BlogStoneService.Stone.class));
        }
        var old=mapper.readValue("{\"id\":\"old\",\"postIds\":[\"1\"],\"x\":500,\"y\":300,\"rotation\":0}",BlogStoneService.Stone.class);
        assertNull(old.shape());
        assertFalse(mapper.writeValueAsString(old).contains("shape"));
    }
    @Test void invalidShapesAreRejected() {
        for(String value:new String[]{"-1","6","1.5","null","\"1\"","2147483648"}){
            assertThrows(RuntimeException.class,()->BlogStoneRequest.parse(mapper.readTree(body(",\"shape\":"+value))));
        }
    }
}
