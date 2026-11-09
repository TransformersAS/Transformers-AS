package com.transformersas.marketplace.notifications.push;

import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;
import java.util.*;

final class PushTestKeys {
    static final String PUBLIC;
    static final String PRIVATE;
    static final String AUTH=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    static {
        try {
            var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair=generator.generateKeyPair();var p=(ECPublicKey)pair.getPublic();byte[] point=new byte[65];point[0]=4;
            System.arraycopy(fixed(p.getW().getAffineX().toByteArray()),0,point,1,32);
            System.arraycopy(fixed(p.getW().getAffineY().toByteArray()),0,point,33,32);
            PUBLIC=Base64.getUrlEncoder().withoutPadding().encodeToString(point);
            PRIVATE=Base64.getUrlEncoder().withoutPadding().encodeToString(fixed(((ECPrivateKey)pair.getPrivate()).getS().toByteArray()));
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    private static byte[] fixed(byte[] bytes){byte[] result=new byte[32];System.arraycopy(bytes,Math.max(0,bytes.length-32),result,Math.max(0,32-bytes.length),Math.min(32,bytes.length));return result;}
}
