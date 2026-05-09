package games.beiming.website.common.core.response;

import java.util.Collections;
import java.util.List;

public class PageResult<T> {

    private List<T> items;
    private long page;
    private long pageSize;
    private long total;

    public PageResult() {
    }

    private PageResult(List<T> items, long page, long pageSize, long total) {
        this.items = items;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
    }

    public static <T> PageResult<T> of(List<T> items, long page, long pageSize, long total) {
        return new PageResult<T>(items, page, pageSize, total);
    }

    public static <T> PageResult<T> empty(long page, long pageSize) {
        return new PageResult<T>(Collections.<T>emptyList(), page, pageSize, 0);
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getPageSize() {
        return pageSize;
    }

    public void setPageSize(long pageSize) {
        this.pageSize = pageSize;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }
}
