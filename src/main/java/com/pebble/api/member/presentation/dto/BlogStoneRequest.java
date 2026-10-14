package com.pebble.api.member.presentation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.pebble.api.global.media.MediaRequest;
import com.pebble.api.member.application.BlogStoneService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public final class BlogStoneRequest {
    private BlogStoneRequest() { }
    public static BlogStoneService.Layout parse(JsonNode json) {
        fields(json, Set.of("version", "stones"));
        if (!json.get("version").canConvertToInt() || !json.get("version").isIntegralNumber() || json.get("version").intValue()<0 || json.get("version").intValue()==Integer.MAX_VALUE) throw MediaRequest.invalid();
        var nodes=json.get("stones");
        if (!nodes.isArray() || nodes.size()>200) throw MediaRequest.invalid();
        var stones=new ArrayList<BlogStoneService.Stone>();
        var seenIds=new HashSet<String>();var seenPosts=new HashSet<String>();
        for(var node:nodes) {
            fields(node, node.has("shape")?Set.of("id", "postIds", "x", "y", "rotation", "shape"):Set.of("id", "postIds", "x", "y", "rotation"));
            Integer shape=null;
            if(node.has("shape")){var value=node.get("shape");if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<0||value.intValue()>5)throw MediaRequest.invalid();shape=value.intValue();}
            var id=node.get("id");
            if(!id.isTextual() || !id.textValue().matches("[a-zA-Z0-9_-]{1,64}") || !seenIds.add(id.textValue())) throw MediaRequest.invalid();
            var sources=node.get("postIds");
            if(!sources.isArray() || sources.isEmpty() || sources.size()>100) throw MediaRequest.invalid();
            var postIds=new ArrayList<String>();
            for(var source:sources) {
                if(!source.isTextual() || !source.textValue().matches("[1-9][0-9]{0,18}") || !seenPosts.add(source.textValue())) throw MediaRequest.invalid();
                try { Long.parseLong(source.textValue()); } catch(NumberFormatException exception) { throw MediaRequest.invalid(); }
                postIds.add(source.textValue());
            }
            if(seenPosts.size()>2000) throw MediaRequest.invalid();
            stones.add(new BlogStoneService.Stone(id.textValue(), java.util.List.copyOf(postIds), number(node.get("x"),0,1000),number(node.get("y"),0,360),number(node.get("rotation"),-180,180),shape));
        }
        return new BlogStoneService.Layout(json.get("version").intValue(), java.util.List.copyOf(stones));
    }
    private static double number(JsonNode node,double min,double max) { if(!node.isNumber() || !Double.isFinite(node.doubleValue()) || node.doubleValue()<min || node.doubleValue()>max)throw MediaRequest.invalid();return node.doubleValue(); }
    private static void fields(JsonNode node,Set<String> allowed) { if(node==null || !node.isObject() || node.size()!=allowed.size())throw MediaRequest.invalid();node.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw MediaRequest.invalid();}); }
}
