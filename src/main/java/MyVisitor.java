import bean.ClassDetails;
import br.com.metricminer2.domain.Commit;
import br.com.metricminer2.domain.Modification;
import br.com.metricminer2.domain.ModificationType;
import br.com.metricminer2.parser.jdt.JDTRunner;
import br.com.metricminer2.persistence.PersistenceMechanism;
import br.com.metricminer2.scm.CommitVisitor;
import br.com.metricminer2.scm.SCMRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Created by Placido Russo on 10/05/2016.
 */
public class MyVisitor implements CommitVisitor {
    private ArrayList<bean.Commit> commits = new ArrayList<>();
    private Map<String, Integer> files;
    //List of list of classDetails present in each commit
    //Repository status for each commit
    private ArrayList<HashMap<String, ArrayList<ClassDetails>>> commitsHistory = new ArrayList<>();

    public MyVisitor() {
        this.files = new Hashtable<String, Integer>();
    }

    public ArrayList<bean.Commit> getCommits() { return commits; }

    public void process(SCMRepository scmRepository, Commit commit, PersistenceMechanism writer) {

        bean.Commit currentCommit = new bean.Commit();
        currentCommit.setHash(commit.getHash());
        currentCommit.setAuthor(commit.getAuthor().toString());
        currentCommit.setTimestamp(commit.getDate().getTimeInMillis());

        //key = path, value = list of classDetails
        HashMap<String, ArrayList<ClassDetails>> modificationsMap = new HashMap<>();
        if (!commitsHistory.isEmpty()) {
            //copying modificationsMap of previous commit into current modificationsMap
            modificationsMap = new HashMap<>(commitsHistory.get(commitsHistory.size()-1));
        }

        for(Modification m : commit.getModifications()) {

            System.out.println("\nModification:");
            ArrayList<ClassDetails> currentList;

            try {
                scmRepository.getScm().checkout(commit.getHash());

                if (!m.fileNameEndsWith(".java")) continue;

                System.out.println(m.getFileName());

                JavaClassVisitor visitor = new JavaClassVisitor(m.getNewPath());
                new JDTRunner().visit(visitor, new ByteArrayInputStream(m.getSourceCode().getBytes()));

                currentList = visitor.getModifiedClasses();
                //modificationsList.addAll(visitor.getModifiedClasses());
            }
            finally {
                scmRepository.getScm().reset();
            }

            String diff = m.getDiff();
            String[] lines = diff.split("\n");
            int lineCounter = 0;

            for(String line : lines) {
                String trimmedLine = line.trim();
                if(trimmedLine.startsWith("@@")) {
                    int startLine = Integer.parseInt(line.substring(line.indexOf('+')+1, line.lastIndexOf(',')));
                    lineCounter = startLine-1;
                }
                else if (trimmedLine.startsWith("+")  && !trimmedLine.startsWith("+++")) {
                    lineCounter++;
                    for (ClassDetails currentClass : currentList) {
                        if (!currentClass.isModified() &&
                                lineCounter >= currentClass.getStartIndex() && lineCounter <= currentClass.getEndIndex()) {
                            currentClass.setModified(true);
                        }
                    }
                }
                else if (trimmedLine.startsWith("-")  && !line.startsWith("---")) {
                    //when a line is removed
                    for (ClassDetails currentClass : currentList) {
                        if (!currentClass.isModified() &&
                                lineCounter >= currentClass.getStartIndex() && lineCounter <= currentClass.getEndIndex()) {
                            currentClass.setModified(true);
                        }
                    }
                }
                else {
                    lineCounter++;
                }

            }

            //Update history of commits
            //first commit case
            if (commitsHistory.isEmpty())
                modificationsMap.put(m.getNewPath(), currentList);
            else {
                //3 cases:
                //1- file added
                if (m.getType() == ModificationType.ADD) {
                    modificationsMap.put(m.getNewPath(), currentList);
                }
                //2- file deleted
                else if (m.getType() == ModificationType.DELETE) {
                    modificationsMap.remove(m.getNewPath());
                }
                //3- file modified
                else if (m.getType() == ModificationType.MODIFY) {
                    modificationsMap.replace(m.getNewPath(), currentList);
                }
            }

            currentCommit.setModifiedClasses(currentList);
            commits.add(currentCommit);

            System.out.println("Nostro commit: "+currentCommit.toString());


            plusOne(m.getNewPath());
        }

        ObjectMapper mapper = new ObjectMapper();

        try {
            mapper.writeValue(new File(".\\commits.json"), commits);
        } catch (IOException e) {
            e.printStackTrace();
        }

        //add modificationsMap to list of commits
        commitsHistory.add(modificationsMap);

        //scrive su file TSV
        writer.write(
                commit.getHash(),
                new SimpleDateFormat("YYYY-MM-dd HH:mm:ss").format(commit.getDate().getTime()),
                commit.getAuthor(),
                files
        );

    }

    public Map<String, Integer> getFiles() {
        return files;
    }

    private void plusOne(String file) {

        if(!files.containsKey(file))
            files.put(file, 0);

        Integer currentQty = files.get(file);
        files.put(file, currentQty + 1);

    }

    public String name() {
        return "MyVisitor";
    }
}
