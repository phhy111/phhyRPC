package com.phhy.rpc.example.api;

import com.phhy.rpc.common.annotation.Sensitive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    private String username;

    @Sensitive
    private String phone;

    @Sensitive
    private String idCard;
}
