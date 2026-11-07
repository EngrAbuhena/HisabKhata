package com.hisabkhata.releaseprobe;

import android.accounts.Account;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Offline smoke probe: uses the installed release's own R8-transformed classes. */
public final class ReleaseProbe extends Instrumentation {
  private Context context;
  private ClassLoader appLoader;
  private Bundle args;
  private final StringBuilder report = new StringBuilder();
  @Override public void onCreate(Bundle b) { super.onCreate(b); args=b; start(); }
  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      context = getTargetContext(); appLoader = context.getClassLoader();
      check((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0, "Target is not a release APK");
      check("com.hisabkhata".equals(context.getPackageName()), "Wrong target package");
      Object fixture=fixture(); testBackupCodec(fixture); testTransfer(fixture); testAuthorization(); testAutomaticWorker();
      report.append("RELEASE PROBE PASS: offline release smoke checks completed\n");
      result.putString("stream", "\n"+report); finish(Activity.RESULT_OK, result);
    } catch (Throwable t) {
      StringWriter sw=new StringWriter(); t.printStackTrace(new PrintWriter(sw));
      result.putString("stream", "\n"+report+"RELEASE PROBE FAIL\n"+sw+"\n");
      finish(Activity.RESULT_CANCELED, result);
    }
  }
  private Class<?> type(String name) throws Exception { return appLoader.loadClass(name); }
  private Object instance(String name) throws Exception { return type(name).getField("INSTANCE").get(null); }
  @SuppressWarnings({"rawtypes", "unchecked"}) private Object enumValue(String cls, String name) throws Exception { return Enum.valueOf((Class)type(cls),name); }
  private static void check(boolean condition, String why) { if(!condition) throw new AssertionError(why); }
  private static byte[] readAll(InputStream in) throws Exception {
    try (InputStream s=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] b=new byte[8192]; int n; while((n=s.read(b))!=-1) out.write(b,0,n); return out.toByteArray();
    }
  }
  private Object fixture() throws Exception {
    Class<?> person=type("com.hisabkhata.data.local.entity.PersonEntity");
    Class<?> personType=type("com.hisabkhata.data.local.entity.PersonType");
    Class<?> loan=type("com.hisabkhata.data.local.entity.LoanEntryEntity");
    Class<?> cash=type("com.hisabkhata.data.local.entity.CashEntryEntity");
    Object p=person.getConstructor(long.class,String.class,String.class,String.class,personType)
      .newInstance(7L,"Fictional Person A","","",enumValue(personType.getName(),"LENT"));
    Object l=loan.getConstructor(long.class,long.class,long.class,long.class,long.class,String.class,long.class,Long.class)
      .newInstance(11L,7L,20000L,5000000L,3000000L,"Example transfer",100L,Long.valueOf(31));
    Object c=cash.getConstructor(long.class,long.class,long.class,long.class,String.class,long.class,Long.class)
      .newInstance(31L,20000L,3000000L,5000000L,"Fictional Person A - Example transfer",100L,Long.valueOf(11));
    return type("com.hisabkhata.domain.backup.DatabaseBackup").getConstructor(long.class,List.class,List.class,List.class)
      .newInstance(123L,Collections.singletonList(p),Collections.singletonList(l),Collections.singletonList(c));
  }
  private void testBackupCodec(Object backup) throws Exception {
    Class<?> bc=type("com.hisabkhata.domain.backup.BackupCodec"); Object codec=instance(bc.getName());
    String json=(String)bc.getMethod("encode",backup.getClass()).invoke(codec,backup);
    Object decoded=bc.getMethod("decode",String.class).invoke(codec,json);
    check(backup.equals(decoded),"Backup JSON round trip failed");
    try { bc.getMethod("decode",String.class).invoke(codec,"not a backup"); throw new AssertionError("Invalid JSON accepted"); }
    catch (InvocationTargetException expected) { /* invalid input rejected */ }
    report.append("PASS: JSON backup round trip and invalid-input rejection\n");
  }
  private Object parse(Object kind, Object table) throws Exception {
    Class<?> k=type("com.hisabkhata.domain.interchange.ImportKind");
    Class<?> tab=type("com.hisabkhata.domain.interchange.ImportedTable");
    Class<?> parser=type("com.hisabkhata.domain.interchange.ImportParser");
    return parser.getMethod("parse",k,tab).invoke(instance(parser.getName()),kind,table);
  }
  private Object readCsv(byte[] bytes) throws Exception {
    Class<?> c=type("com.hisabkhata.domain.interchange.CsvTableCodec");
    return c.getMethod("read",byte[].class).invoke(instance(c.getName()),(Object)bytes);
  }
  private Object readXlsx(byte[] bytes) throws Exception {
    Class<?> c=type("com.hisabkhata.domain.interchange.XlsxTableCodec");
    return c.getMethod("read",byte[].class,java.io.File.class).invoke(instance(c.getName()),bytes,context.getCacheDir());
  }
  private List<?> lines(Object preview) throws Exception { return (List<?>)preview.getClass().getMethod("getLines").invoke(preview); }
  private Object record(Object line) throws Exception { return line.getClass().getMethod("getRecord").invoke(line); }
  private Object singletonByType(String className) throws Exception {
    Class<?> c=type(className);
    for (Field f:c.getDeclaredFields()) if(Modifier.isStatic(f.getModifiers()) && f.getType()==c) { f.setAccessible(true); return f.get(null); }
    throw new IllegalStateException("No singleton on "+className);
  }
  private Object suspend(Object receiver,String method,Object...params) throws Exception {
    Method selected=null;
    for(Method m:receiver.getClass().getMethods()) if(m.getName().equals(method) && m.getParameterTypes().length==params.length+1) { selected=m; break; }
    check(selected!=null,"No suspend method "+method);
    Class<?> continuation=selected.getParameterTypes()[params.length];
    final Object empty=singletonByType(args.getString("emptyContext"));
    final CountDownLatch done=new CountDownLatch(1); final AtomicReference<Object> value=new AtomicReference<>();
    Object proxy=Proxy.newProxyInstance(appLoader,new Class<?>[]{continuation},(obj,m,a)->{
      if(m.getName().equals("getContext")) return empty;
      if(m.getName().equals("resumeWith")) { value.set(a[0]); done.countDown(); return null; }
      if(m.getName().equals("toString")) return "ReleaseProbeContinuation";
      if(m.getName().equals("hashCode")) return System.identityHashCode(obj);
      if(m.getName().equals("equals")) return obj==a[0];
      throw new UnsupportedOperationException("Unexpected continuation method "+m.getName());
    });
    Object[] all=Arrays.copyOf(params,params.length+1); all[params.length]=proxy;
    Object result=selected.invoke(receiver,all);
    if(result!=null && result.getClass().getName().equals(args.getString("suspendedClass"))) {
      check(done.await(60,TimeUnit.SECONDS),"Timed out in "+method); result=value.get();
    }
    if(result!=null && result.getClass().getName().equals(args.getString("failureClass"))) {
      for(Field f:result.getClass().getDeclaredFields()) if(Throwable.class.isAssignableFrom(f.getType())) {
        f.setAccessible(true); Throwable t=(Throwable)f.get(result); throw new RuntimeException("Suspending call failed: "+method,t);
      }
      throw new AssertionError("Suspending call failed: "+method);
    }
    return result;
  }
  private void testTransfer(Object backup) throws Exception {
    Object cashKind=enumValue("com.hisabkhata.domain.interchange.ImportKind","CASH");
    Object loanKind=enumValue("com.hisabkhata.domain.interchange.ImportKind","LOAN");
    Object cashCsv=parse(cashKind,readCsv(readAll(context.getAssets().open("samples/cash_sample.csv"))));
    Object cashExcel=parse(cashKind,readXlsx(readAll(context.getAssets().open("samples/cash_sample.xlsx"))));
    check(lines(cashCsv).size()==3 && lines(cashExcel).size()==3,"Cash sample row counts");
    for(int i=0;i<3;i++) check(Objects.equals(record(lines(cashCsv).get(i)),record(lines(cashExcel).get(i))),"CSV/XLSX cash records differ");
    Object loanCsv=parse(loanKind,readCsv(readAll(context.getAssets().open("samples/loan_sample.csv"))));
    Object loanExcel=parse(loanKind,readXlsx(readAll(context.getAssets().open("samples/loan_sample.xlsx"))));
    check(lines(loanCsv).size()==1 && Objects.equals(record(lines(loanCsv).get(0)),record(lines(loanExcel).get(0))),"CSV/XLSX loan samples differ");
    report.append("PASS: fictional CSV and XLSX imports\n");
    Class<?> export=type("com.hisabkhata.data.transfer.ExportData");
    Object writer=export.getConstructor(Context.class,backup.getClass()).newInstance(context,backup);
    Object table=export.getMethod("cash",Long.class,Long.class).invoke(writer,null,null);
    byte[] csv=(byte[])export.getMethod("csv",table.getClass(),boolean.class).invoke(writer,table,false);
    Object csvOut=parse(cashKind,readCsv(csv)); check(lines(csvOut).size()==1 && record(lines(csvOut).get(0))!=null,"CSV export did not round trip");
    Class<?> pair=type(args.getString("pairClass")); Object pairValue=pair.getConstructor(Object.class,Object.class).newInstance("Cash Book",table);
    byte[] xlsx=(byte[])export.getMethod("excel",List.class).invoke(writer,Collections.singletonList(pairValue));
    Object xlsxOut=parse(cashKind,readXlsx(xlsx)); check(lines(xlsxOut).size()==1 && Objects.equals(record(lines(csvOut).get(0)),record(lines(xlsxOut).get(0))),"Excel export did not round trip");
    Class<?> pdf=type("com.hisabkhata.data.transfer.PdfLedgerWriter");
    byte[] pdfBytes=(byte[])pdf.getMethod("write",table.getClass()).invoke(instance(pdf.getName()),table);
    check(pdfBytes.length>1000 && new String(pdfBytes,0,4,"UTF-8").equals("%PDF"),"PDF export invalid");
    report.append("PASS: CSV/XLSX export round trips and PDF generation\n");
    Class<?> dbType=type("com.hisabkhata.data.local.HisabDatabase"),room=type("androidx.room.Room");
    Object builder=room.getMethod("inMemoryDatabaseBuilder",Context.class,Class.class).invoke(null,context,dbType);
    builder.getClass().getMethod("allowMainThreadQueries").invoke(builder);
    Object db=builder.getClass().getMethod("build").invoke(builder);
    try {
      Class<?> repoType=type("com.hisabkhata.data.transfer.DataTransferRepository");
      Object repo=repoType.getConstructor(dbType,Context.class).newInstance(db,context);
      suspend(repo,"restore",backup,enumValue("com.hisabkhata.data.transfer.RestoreMode","REPLACE"));
      Object once=suspend(repo,"snapshot");
      check(((List<?>)once.getClass().getMethod("getPeople").invoke(once)).size()==1,"Replace restore people mismatch");
      check(((List<?>)once.getClass().getMethod("getLoans").invoke(once)).size()==1,"Replace restore loans mismatch");
      check(((List<?>)once.getClass().getMethod("getCash").invoke(once)).size()==1,"Replace restore cash mismatch");
      suspend(repo,"restore",backup,enumValue("com.hisabkhata.data.transfer.RestoreMode","MERGE"));
      Object twice=suspend(repo,"snapshot");
      List<?> loans=(List<?>)twice.getClass().getMethod("getLoans").invoke(twice);
      List<?> cash=(List<?>)twice.getClass().getMethod("getCash").invoke(twice);
      check(loans.size()==2 && cash.size()==2,"Merge restore rows mismatch");
      for(Object loan:loans) {
        long id=(Long)loan.getClass().getMethod("getId").invoke(loan);
        Long cashId=(Long)loan.getClass().getMethod("getLinkedCashEntryId").invoke(loan); boolean found=false;
        for(Object c:cash) if(Objects.equals(cashId,c.getClass().getMethod("getId").invoke(c)) && Objects.equals(id,c.getClass().getMethod("getLinkedLoanEntryId").invoke(c))) found=true;
        check(found,"Restored cross-ledger links broken");
      }
      Object firstImport=suspend(repo,"importValid",loanCsv,true,false);
      check(((Integer)firstImport.getClass().getMethod("getImported").invoke(firstImport))==1,"Loan import did not save");
      Object secondImport=suspend(repo,"importValid",loanCsv,true,false);
      check(((Integer)secondImport.getClass().getMethod("getImported").invoke(secondImport))==0 &&
        ((Integer)secondImport.getClass().getMethod("getDuplicatesSkipped").invoke(secondImport))==1,"Duplicate import was not skipped");
      report.append("PASS: in-memory Room replace/merge restore, linked records, and duplicate-safe import\n");
    } finally { db.getClass().getMethod("close").invoke(db); }
  }
  private void testAutomaticWorker() throws Exception {
    // Never trigger an upload if a real test device already has automatic backup enabled.
    if (context.getSharedPreferences("hisab_settings", Context.MODE_PRIVATE).getBoolean("automatic_drive_enabled", false)) {
      report.append("SKIP: automatic worker execution (already enabled on this device)\n"); return;
    }
    Class<?> worker=type("com.hisabkhata.data.drive.AutomaticDriveBackupWorker");
    Class<?> builderType=type("androidx.work.OneTimeWorkRequest$Builder");
    Object builder=builderType.getConstructor(Class.class).newInstance(worker);
    Object request=builderType.getMethod("build").invoke(builder);
    Class<?> requestType=type("androidx.work.WorkRequest"), managerType=type("androidx.work.WorkManager");
    Object manager=managerType.getMethod("getInstance",Context.class).invoke(null,context);
    Object operation=managerType.getMethod("enqueue",requestType).invoke(manager,request);
    ((Future<?>)type("androidx.work.Operation").getMethod("getResult").invoke(operation)).get(30,TimeUnit.SECONDS);
    UUID id=(UUID)requestType.getMethod("getId").invoke(request);
    long deadline=System.currentTimeMillis()+120_000L; String state="";
    while(System.currentTimeMillis()<deadline) {
      Object future=managerType.getMethod("getWorkInfoById",UUID.class).invoke(manager,id);
      Object info=((Future<?>)future).get(30,TimeUnit.SECONDS);
      if(info!=null) state=String.valueOf(type("androidx.work.WorkInfo").getMethod("getState").invoke(info));
      if(state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CANCELLED")) break;
      Thread.sleep(250L);
    }
    check(state.equals("SUCCEEDED"),"Disabled automatic WorkManager worker did not run safely: "+state);
    report.append("PASS: R8 WorkManager instantiates and runs disabled worker as a no-op\n");
  }

  private void testAuthorization() throws Exception {
    Class<?> scope=type("com.google.android.gms.common.api.Scope");
    Object driveScope=scope.getConstructor(String.class).newInstance("https://www.googleapis.com/auth/drive.appdata");
    Class<?> request=type("com.google.android.gms.auth.api.identity.AuthorizationRequest");
    Object builder=request.getMethod("builder").invoke(null);
    builder.getClass().getMethod("setAccount",Account.class).invoke(builder,new Account("fictional@example.invalid","com.google"));
    builder.getClass().getMethod("setRequestedScopes",List.class).invoke(builder,Collections.singletonList(driveScope));
    check(builder.getClass().getMethod("build").invoke(builder)!=null,"Authorization request missing");
    Class<?> identity=type("com.google.android.gms.auth.api.identity.Identity");
    check(identity.getMethod("getAuthorizationClient",Context.class).invoke(null,context)!=null,"Identity client missing");
    Class<?> drive=type("com.hisabkhata.data.drive.DriveBackupService"); Object service=drive.getConstructor(Context.class).newInstance(context);
    try { drive.getMethod("download",String.class,String.class).invoke(service,"fictional-token","../invalid"); throw new AssertionError("Invalid Drive id accepted"); }
    catch(InvocationTargetException expected) { check(expected.getCause().getClass().getName().endsWith("DriveRequestException"),"Unexpected validation error"); }
    report.append("PASS: Google Identity request/client and offline Drive ID validation (no live sign-in)\n");
  }
}
