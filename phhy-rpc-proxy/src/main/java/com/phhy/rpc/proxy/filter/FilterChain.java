package com.phhy.rpc.proxy.filter;

import com.phhy.rpc.common.exception.RpcException;
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

    /**
     * 插入到链首，适用于认证等必须最先执行的过滤器。
     */
    public void addFirst(Filter filter) {
        filters.add(0, filter);
    }

    public void doFilterBefore(RpcRequest request) {
        for (Filter filter : filters) {
            try {
                filter.doFilterBefore(request);
            } catch (RpcException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Filter 前置处理异常： {}", filter.getClass().getSimpleName(), e);
            }
        }
    }

    public void doFilterAfter(RpcRequest request, RpcResponse response) {
        for (Filter filter : filters) {
            try {
                filter.doFilterAfter(request, response);
            } catch (RpcException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Filter 后置处理异常： {}", filter.getClass().getSimpleName(), e);
            }
        }
    }
}
