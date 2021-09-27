package com.itcodebox.notebooks.service.impl;

import com.intellij.openapi.components.ServiceManager;
import com.itcodebox.notebooks.dao.SearchRecordDao;
import com.itcodebox.notebooks.dao.impl.SearchRecordDaoImpl;
import com.itcodebox.notebooks.entity.SearchMode;
import com.itcodebox.notebooks.entity.SearchRecord;
import com.itcodebox.notebooks.service.DatabaseBasicService;
import com.itcodebox.notebooks.service.SearchRecordService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * @author LeeWyatt
 */
public class SearchRecordServiceImpl implements SearchRecordService {
    private final DatabaseBasicService databaseBasicService =  ServiceManager.getService(DatabaseBasicService.class);
    private final SearchRecordDao searchRecordDao = SearchRecordDaoImpl.getInstance();

    private SearchRecordServiceImpl() {
    }

    public static SearchRecordServiceImpl getInstance() {
        return  ServiceManager.getService(SearchRecordServiceImpl.class);
    }

    @Override
    public List<SearchRecord> searchKeywords(String keywords, SearchMode searchMode) {
        Connection conn = null;
        try {
            conn = databaseBasicService.getConnection();
            return searchRecordDao.searchKeywords(conn, keywords,searchMode);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        } finally {
            databaseBasicService.closeResource(conn, null, null);
        }
        return null;
    }
}
