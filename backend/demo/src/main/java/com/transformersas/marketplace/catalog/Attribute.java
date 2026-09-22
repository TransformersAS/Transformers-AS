package com.transformersas.marketplace.catalog;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Atributo de un producto (Color, Talla...) con la lista de valores que se permiten para él. */
@Entity
@Table(name = "attributes")
@Getter
@Setter
@NoArgsConstructor
public class Attribute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    // Los valores se guardan y se borran junto con su atributo.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "attribute_id", nullable = false)
    private List<AttributeValue> values = new ArrayList<>();
}
