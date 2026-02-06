package com.example.myapplication.model;

/**
 * Test User class for DataBinding source change test
 * This is the MODIFIED version with changed field names
 */
public class User {
    // Changed from 'name' to 'userName'
    public final String userName;

    // Changed from 'age' to 'userAge'
    public final int userAge;

    public User(String userName, int userAge) {
        this.userName = userName;
        this.userAge = userAge;
    }

    public String getUserName() {
        return userName;
    }

    public int getUserAge() {
        return userAge;
    }
}
