package com.crosscert.passkey.admin.mds;

public record MdsStatusView(long version, String nextUpdate, String fetchedAt) {}
