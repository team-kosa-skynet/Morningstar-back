package com.gaebang.backend.domain.payment.dto.response;

import lombok.Getter;

@Getter
public class Amount {

    private int total;
    private int tax_free;
    private int tax;
    private int point;
    private int discount;
    private int green_deposit;

}