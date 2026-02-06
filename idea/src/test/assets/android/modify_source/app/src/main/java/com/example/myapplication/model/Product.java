package com.example.myapplication.model;

/**
 * Test Product class for DataBinding class name change test
 * This is the NEW class name (renamed from ProductModel)
 */
public class Product {
    public final String productName;
    public final double price;

    public Product(String productName, double price) {
        this.productName = productName;
        this.price = price;
    }

    public String getProductName() {
        return productName;
    }

    public double getPrice() {
        return price;
    }
}
