package com.olx.ads.adapter.in.web;

import java.util.List;

/** One page of the listing; {@code next_cursor} is null on the last page. */
record AdPageResponse(List<AdResponse> data, String nextCursor) {}
