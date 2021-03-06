package com.itcodebox.notebooks.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.itcodebox.notebooks.constant.PluginConstant;
import com.itcodebox.notebooks.entity.Chapter;
import com.itcodebox.notebooks.entity.Note;
import com.itcodebox.notebooks.entity.Notebook;
import com.itcodebox.notebooks.projectservice.RecordListener;
import com.itcodebox.notebooks.service.impl.ChapterServiceImpl;
import com.itcodebox.notebooks.service.impl.NoteServiceImpl;
import com.itcodebox.notebooks.service.impl.NotebookServiceImpl;
import com.itcodebox.notebooks.ui.notify.NotifyUtil;
import com.itcodebox.notebooks.ui.toolsettings.AppSettingsChangedListener;
import com.itcodebox.notebooks.ui.toolsettings.AppSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.itcodebox.notebooks.utils.NotebooksBundle.message;

/**
 * @author LeeWyatt
 */
public class ImportUtil {
    private static final int CHOOSE_CLOSE = -1;
    private static final int CHOOSE_OVERWRITE = 0;
    private static final int CHOOSE_SKIP = 1;
    private static final int CHOOSE_UPDATE = 2;
    private static final int CHOOSE_RENAME = 3;

    public static void importJsonFile(Project project, VirtualFile selectedFile) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, message("notify.import.backgroundTask.title"), true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Import images");
                //?????????????????????
                String fileName = selectedFile.getName();
                String imageDir = fileName.substring(0, fileName.lastIndexOf(".")) + ".assets";
                //Path imageDirPath = selectedFile.getParent().toNioPath().resolve(imageDir);

                Path imageDirPath =  Paths.get(selectedFile.getParent().getPath()).resolve(imageDir);
                if (imageDirPath.toFile().exists()) {
                    try {
                        CustomFileUtil.copyDirectory(imageDirPath.toFile(),PluginConstant.IMAGE_DIRECTORY_PATH.toFile());
                    } catch (IOException exception) {
                        exception.printStackTrace();
                    }
                }
                //??????????????????
                publishReadOnlyMode(project, true);
                // ???????????????????????????, ????????????????????????????????????,??????????????????
                indicator.checkCanceled();
                indicator.setFraction(0.0);
                indicator.setIndeterminate(false);
                // ??????JSON??????java??????
                LinkedHashMap<Notebook, LinkedHashMap<Chapter, List<Note>>> notebookCollection = processJson(project, selectedFile);
                if (notebookCollection == null) {
                    return;
                }
                Set<Map.Entry<Notebook, LinkedHashMap<Chapter, List<Note>>>> entries = notebookCollection.entrySet();
                int size = entries.size();
                if (size == 0) {
                    return;
                }
                int index = 0;
                boolean doNotAsk = false;
                int defaultChoose = Integer.MIN_VALUE;
                NotebookServiceImpl notebookService = NotebookServiceImpl.getInstance();
                for (Map.Entry<Notebook, LinkedHashMap<Chapter, List<Note>>> entry : entries) {
                    indicator.checkCanceled();
                    Notebook notebookInJson = entry.getKey();
                    Notebook notebookInDb = notebookService.findByTitle(notebookInJson.getTitle());
                    // ????????????,??????????????????
                    if (notebookInDb != null) {
                        indicator.setText("Import " + notebookInDb.getTitle());
                        if (doNotAsk) {
                            if (nameConflictHandler(indicator, entry, notebookInDb, defaultChoose)) {
                                continue;
                            }
                        } else {
                            AtomicReference<UserChoose> chooseAtomicReference = new AtomicReference<>();
                            Application application = ApplicationManager.getApplication();
                            if (application.isDispatchThread()) {
                                chooseAtomicReference.set(nameConflictDialog(notebookInJson.getTitle()));
                            } else {
                                application.invokeAndWait(() -> chooseAtomicReference.set(nameConflictDialog(notebookInJson.getTitle())));
                            }
                            UserChoose userChoose = chooseAtomicReference.get();
                            if (userChoose.getExitCode() == CHOOSE_CLOSE) {
                                //??????, ?????????????????????
                                NotifyUtil.showInfoNotification(project, PluginConstant.NOTIFICATION_ID_IMPORT_EXPORT, message("notify.import.close.title"), message("notify.import.close.message"));
                                break;
                            }

                            if (userChoose.isDoNotAsk()) {
                                doNotAsk = true;
                                defaultChoose = userChoose.getExitCode();
                            }
                            if (nameConflictHandler(indicator, entry, notebookInDb, userChoose.getExitCode())) {
                                continue;
                            }
                        }

                    } else {
                        indicator.setText("Import " + entry.getKey().getTitle());
                        addNotebookFromJson(indicator, entry);
                    }
                    double fraction = (++index) * 1.0 / size;
                    indicator.setFraction(fraction);
                }
                NotifyUtil.showInfoNotification(project, PluginConstant.NOTIFICATION_ID_IMPORT_EXPORT, message("notify.import.success.title"), message("notify.import.success.message"));

            }

            /**
             * ???????????????, ??????,????????????,?????????????????????onFinished???
             * ?????????onFinished???????????????
             */
            @Override
            public void onFinished() {
                //??????Table
                ApplicationManager.getApplication().getMessageBus().syncPublisher(RecordListener.TOPIC)
                        .onRefresh();
                //??????????????????
                publishReadOnlyMode(project, false);
            }

            @Override
            public void onThrowable(@NotNull Throwable error) {
                //error.printStackTrace();
                NotifyUtil.showErrorNotification(project, PluginConstant.NOTIFICATION_ID_IMPORT_EXPORT, message("notify.import.throwable.title"), message("notify.import.throwable.message"), error.getMessage());
            }
        });
    }

    public static void publishReadOnlyMode(Project project, boolean b) {
        AppSettingsState.getInstance().readOnlyMode = b;
        ApplicationManager.getApplication()
                .getMessageBus()
                .syncPublisher(AppSettingsChangedListener.TOPIC)
                .onSetReadOnlyMode(project, b);
    }

    /**
     * ??????JSON?????????Java??????
     *
     * @param project      ????????????
     * @param selectedFile ???????????????
     * @return java??????
     */
    @Nullable
    private static LinkedHashMap<Notebook, LinkedHashMap<Chapter, List<Note>>> processJson(Project project, VirtualFile selectedFile) {
        LinkedHashMap<Notebook, LinkedHashMap<Chapter, List<Note>>> notebooksCollection = null;
        try {
            notebooksCollection = new ObjectMapper().readValue(new File(selectedFile.getPath()), new TypeReference<LinkedHashMap<Notebook, LinkedHashMap<Chapter, List<Note>>>>() {
            });
        } catch (JsonProcessingException e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String msg = sw.toString();
            NotifyUtil.showImportErrorNotification(project, PluginConstant.NOTIFICATION_ID_IMPORT_EXPORT,
                    message("notify.import.jsonException.title"),
                    message("notify.import.jsonException.message"),
                    e.getMessage() + System.lineSeparator() + msg);
        } catch (IOException exception) {
            NotifyUtil.showErrorNotification(project, PluginConstant.NOTIFICATION_ID_IMPORT_EXPORT,
                    message("notify.import.jsonIOException.title"), message("notify.import.jsonIOException.message"), exception.getMessage());
        }
        return notebooksCollection;
    }

    /**
     * ???Notebook???????????????, ??????????????????????????????
     *
     * @param indicator    ?????????, ???????????????????????????????????????????????????.???????????????.??????????????????????????????
     * @param entry        ??????
     * @param notebookInDb ??????????????????????????????Notebook
     * @param userChoose   ???????????????????????????
     * @return boolean ????????????for??????????????????????????????????????????,??????????????????. ?????????true,??????for??????continue
     */
    private static boolean nameConflictHandler(ProgressIndicator indicator, Map.Entry<Notebook, LinkedHashMap<Chapter, List<Note>>> entry, Notebook notebookInDb, int userChoose) {
        indicator.checkCanceled();
        if (userChoose == CHOOSE_OVERWRITE) {
            //?????????,????????????
            NotebookServiceImpl.getInstance().delete(notebookInDb.getId());
            //?????????,????????????
            addNotebookFromJson(indicator, entry);
        } else if (userChoose == CHOOSE_SKIP) {
            //??????
            return true;
        } else if (userChoose == CHOOSE_UPDATE) {
            indicator.setText("Update " + notebookInDb.getTitle());
            updateNotesFromJson(indicator, notebookInDb, entry);
        } else if (userChoose == CHOOSE_RENAME) {
            Random random = new Random();
            String newTitle = notebookInDb.getTitle() + "_" + System.currentTimeMillis() + (random.nextInt(900) + 100);
            indicator.setText("Import " + newTitle);
            entry.getKey().setTitle(newTitle);
            addNotebookFromJson(indicator, entry);
        }
        return false;
    }

    /**
     * ???????????????????????? ?????????Notebook??? ,??????????????????,????????????????????????
     *
     * @param indicator ?????????
     * @param entry     ??????
     */
    private static void addNotebookFromJson(ProgressIndicator indicator, Map.Entry<Notebook, LinkedHashMap<Chapter, List<Note>>> entry) {
        //???????????????, ????????????????????????
        indicator.checkCanceled();
        Notebook notebookInDb = NotebookServiceImpl.getInstance().insert(entry.getKey());
        LinkedHashMap<Chapter, List<Note>> hashMap = entry.getValue();
        Set<Map.Entry<Chapter, List<Note>>> chapterListEntries = hashMap.entrySet();
        for (Map.Entry<Chapter, List<Note>> chapterListEntry : chapterListEntries) {
            indicator.checkCanceled();
            Chapter tempChapter = chapterListEntry.getKey();
            tempChapter.setNotebookId(notebookInDb.getId());
            Chapter chapter = ChapterServiceImpl.getInstance().insert(tempChapter);
            addNotesFromJson(indicator, notebookInDb, chapterListEntry, chapter);
        }
    }

    /**
     * ??????????????????????????????Notebook???. ?????????????????????, ??????????????????????????????????????????????????????Note???,????????????????????????????????????Note
     *
     * @param indicator    ?????????
     * @param notebookInDb ???????????????Notebook
     * @param entry        ??????
     */
    private static void updateNotesFromJson(ProgressIndicator indicator, Notebook notebookInDb, Map.Entry<Notebook, LinkedHashMap<Chapter, List<Note>>> entry) {
        ChapterServiceImpl chapterService = ChapterServiceImpl.getInstance();
        NoteServiceImpl noteService = NoteServiceImpl.getInstance();
        LinkedHashMap<Chapter, List<Note>> hashMap = entry.getValue();
        Set<Map.Entry<Chapter, List<Note>>> chapterListEntries = hashMap.entrySet();
        for (Map.Entry<Chapter, List<Note>> chapterListEntry : chapterListEntries) {
            Chapter chapterInJson = chapterListEntry.getKey();
            Chapter chapterInDb = chapterService.findByTitle(chapterInJson.getTitle(), notebookInDb.getId());

            List<Note> updateNoteList = new ArrayList<>();

            //?????????????????????????????????, ????????????Note?????????
            if (chapterInDb == null) {
                chapterInDb = chapterService.insert(chapterInJson);
                chapterInDb.setNotebookId(notebookInDb.getId());
                addNotesFromJson(indicator, notebookInDb, chapterListEntry, chapterInDb);
            } else {
                //?????????????????????????????????, ???????????????????????????
                List<Note> noteList = chapterListEntry.getValue();
                List<Note> insertNewNoteList = new ArrayList<>();
                for (Note note : noteList) {
                    indicator.checkCanceled();
                    Note noteInDb = noteService.findByTitle(note.getTitle(), chapterInDb.getId());
                    // ?????????????????????????????????, ??????????????????,???????????????
                    if (noteInDb != null) {
                        if (note.getUpdateTime() > noteInDb.getUpdateTime()){
                            // ???????????????showOrder; notebookId; chapterId; id; title
                            noteInDb.setContent(note.getContent());
                            noteInDb.setSource(note.getSource());
                            noteInDb.setDescription(note.getDescription());
                            noteInDb.setType(note.getType());
                            noteInDb.setCreateTime(note.getCreateTime());
                            noteInDb.setUpdateTime(note.getUpdateTime());
                            updateNoteList.add(noteInDb);
                        }
                        //????????????????????????????????????,????????????
                    } else {
                        // ?????????????????????????????????. ??????json???Note???????????????
                        note.setNotebookId(notebookInDb.getId());
                        note.setChapterId(chapterInDb.getId());
                        insertNewNoteList.add(note);
                    }
                }
                indicator.checkCanceled();
                //?????????
                Note[] updateNotes = new Note[updateNoteList.size()];
                noteService.update(updateNoteList.toArray(updateNotes));

                //???????????????
                Note[] newNotes = new Note[insertNewNoteList.size()];
                noteService.insert(insertNewNoteList.toArray(newNotes));
            }
        }
    }

    /**
     * ???????????????,????????????Notes
     *
     * @param indicator        ?????????
     * @param notebookInDb     ???????????????Notebook
     * @param chapterListEntry ???????????????Note??????
     * @param chapterInDb      ????????????
     */
    private static void addNotesFromJson(ProgressIndicator indicator, Notebook notebookInDb, Map.Entry<Chapter, List<Note>> chapterListEntry, Chapter chapterInDb) {
        List<Note> noteList = chapterListEntry.getValue();
        Note[] notes = new Note[noteList.size()];
        for (Note note : noteList) {
            note.setNotebookId(notebookInDb.getId());
            note.setChapterId(chapterInDb.getId());
        }
        for (int j = 0; j < noteList.size(); j++) {
            Note note = noteList.get(j);
            note.setNotebookId(notebookInDb.getId());
            note.setChapterId(chapterInDb.getId());
            notes[j] = note;
        }
        indicator.checkCanceled();
        NoteServiceImpl.getInstance().insert(notes);
    }

    /**
     * ??????Notebook?????????,????????????????????????
     *
     * @param title Notebook?????????
     * @return ???????????????
     */
    private static UserChoose nameConflictDialog(String title) {
        UserChoose userChoose = new UserChoose();
        Messages.showDialog(
                "<html><body>" +
                        message("notify.import.nameConflict.message1") + "<br>" +
                        "<b>" + message("notify.import.nameConflict.message2") + "</b>" + title + "<br/>" +
                        "</html></body>"
                , message("notify.import.nameConflict.title"),
                new String[]{
                        //0
                        message("notify.import.nameConflict.chooseOverwrite"),
                        //1
                        message("notify.import.nameConflict.chooseSkip"),
                        //2
                        message("notify.import.nameConflict.chooseUpdate"),
                        //3
                        message("notify.import.nameConflict.chooseAutoRename")
                }, 1, Messages.getWarningIcon(), new DialogWrapper.DoNotAskOption.Adapter() {

                    @Override
                    public void rememberChoice(boolean isSelected, int exitCode) {
                        userChoose.setDoNotAsk(isSelected);
                        userChoose.setExitCode(exitCode);
                    }

                    @Override
                    public @NotNull String getDoNotShowMessage() {
                        return message("notify.import.nameConflict.rememberChoose");
                    }
                }
        );
        return userChoose;
    }

    private static class UserChoose {
        private boolean doNotAsk;
        private int exitCode;

        public boolean isDoNotAsk() {
            return doNotAsk;
        }

        public void setDoNotAsk(boolean doNotAsk) {
            this.doNotAsk = doNotAsk;
        }

        public int getExitCode() {
            return exitCode;
        }

        public void setExitCode(int exitCode) {
            this.exitCode = exitCode;
        }

        public UserChoose() {
        }

        @Override
        public String toString() {
            return "Choose{" +
                    "doNotAsk=" + doNotAsk +
                    ", exitCode=" + exitCode +
                    '}';
        }
    }
}
