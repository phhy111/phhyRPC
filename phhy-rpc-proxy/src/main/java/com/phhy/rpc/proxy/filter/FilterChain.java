package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.model.RpcRequest;
import com.phhy.rpc.common.model.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class FilterChain {

    private final List<Filter> filters = new ArrayList<>();

    public void addFilter(Filter filter) {
        filters.add(filter);
    }

    public void doFilterBefore(RpcRequest request) {
        for (Filter filter : filters) {
            try {
                filter.doFilterBefore(request);
            } catch (Exception e) {
                log.warn("错误前的过滤： {}", filter.getClass().getSimpleName(), e);
            }
        }
    }

    public void doFilterAfter(RpcRequest request, RpcResponse response) {
        for (Filter filter : filters) {
            try {
                filter.doFilterAfter(request, response);
            } catch (Exception e) {
                log.warn("错误后过滤： {}", filter.getClass().getSimpleName(), e);
            }
        }
    }
}
