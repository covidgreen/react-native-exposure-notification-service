package ie.gov.tracing.common;

public class NfTask<T> {
    private com.google.android.gms.tasks.Task gmsTask;
    private com.huawei.hmf.tasks.Task hmsTask;

    public NfTask(com.google.android.gms.tasks.Task task) {
        gmsTask = task;
    }

    public NfTask(com.huawei.hmf.tasks.Task task) {
        hmsTask = task;
    }

    public com.google.android.gms.tasks.Task getGMSTask() {
        return gmsTask;
    }

    public com.huawei.hmf.tasks.Task getHMSTask() {
        return hmsTask;
    }
}
