package com.example.marketplace.catalog.infrastructure;

import com.example.marketplace.catalog.api.CatalogException;

final class CatalogValues {
    private CatalogValues() { }
    static String text(String value,int limit,boolean required) {
        return text(value,limit,required,false);
    }
    static String description(String value) {
        return text(value==null?null:value.replace("\r\n","\n").replace('\r','\n'),4000,false,true);
    }
    private static String text(String value,int limit,boolean required,boolean multiline) {
        if(value==null) { throw new CatalogException(400); }
        String s=value.strip();
        if(required&&s.isEmpty() || s.codePointCount(0,s.length())>limit || s.codePoints().anyMatch(c->Character.isISOControl(c)&&!(multiline&&(c=='\n'||c=='\t'))||c>=0xD800&&c<=0xDFFF)) { throw new CatalogException(400); }
        return s;
    }
    static void version(Long version) {
        if(version==null||version<0||version>9007199254740990L) { throw new CatalogException(400); }
    }
    static void page(int page) { if(page<0||page>10000) { throw new CatalogException(400); } }
}
