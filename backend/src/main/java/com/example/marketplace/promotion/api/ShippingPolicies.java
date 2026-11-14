package com.example.marketplace.promotion.api;

/** Checkout retains the current policy lock through the original order commit. */
public interface ShippingPolicies {
    record Policy(long version,long baseFee,long freeThreshold) {
        public String quoteVersion() {
            return version==1 ? "P07-C-1" : "SHIP-"+version+"-"+baseFee+"-"+freeThreshold;
        }
    }
    Policy current();
    Policy lockForCheckout();
}
