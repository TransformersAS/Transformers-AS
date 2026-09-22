package com.transformersas.marketplace.address;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // NULL only for legacy data whose owner cannot be established safely.
    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false)
    private String recipientName;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String department;

    @Column(length = 50)
    private String postalCode;

    @Column(nullable = false, length = 50)
    private String phone;
}