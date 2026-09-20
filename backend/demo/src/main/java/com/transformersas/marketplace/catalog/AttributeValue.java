package com.transformersas.marketplace.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "attribute_values")
@Getter
@NoArgsConstructor
public class AttributeValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "value_text", nullable = false, length = 100)
    private String value;

    public AttributeValue(String value) {
        this.value = value;
    }
}
