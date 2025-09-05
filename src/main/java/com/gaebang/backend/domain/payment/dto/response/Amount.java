package com.gaebang.backend.domain.payment.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Amount {

    private int total;
    private int tax_free;
    private int tax;
    private int point;
    private int discount;
    private int green_deposit;

}