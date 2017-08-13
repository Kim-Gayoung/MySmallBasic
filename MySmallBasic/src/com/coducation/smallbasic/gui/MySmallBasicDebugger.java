package com.coducation.smallbasic.gui;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jdiscript.JDIScript;
import org.jdiscript.util.VMLauncher;

import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.InvocationException;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.Value;

public class MySmallBasicDebugger extends MySmallBasicDebuggerModel implements Runnable {
	private JDIScript jdiScript;
	private int previousLineNum = Integer.MAX_VALUE;
	private boolean isStepState = false;

	private String HOME = "./";

	public MySmallBasicDebugger(MySmallBasicDebuggerClientModel debuggerClient, String filePath,
			Set<Integer> breakPoints) {
		super(debuggerClient, filePath, breakPoints);

		String main = "com.coducation.smallbasic.MySmallBasicMain -gui " + filePath;
		StringBuilder options = new StringBuilder("-cp ");
		options.append(HOME + "bin");
		MySmallBasicGUI.addJarFile(options, HOME, HOME + "//lib");

		jdiScript = new JDIScript(new VMLauncher(options.toString(), main).start());

		// breakpoint info
		String breakPointClass = "com.coducation.smallbasic.Eval";
		String breakPointMethod = "eval";

		jdiScript.onMethodInvocation(breakPointClass, breakPointMethod,
				"(Lcom/coducation/smallbasic/BasicBlockEnv;Lcom/coducation/smallbasic/Env;Lcom/coducation/smallbasic/Stmt;)V",
				methodEvent -> {
					// 스몰 베이직 프로그램의 lineNumber 구하기
					int lineNum = Integer.MAX_VALUE;

					try {
						Field linenoField = jdiScript.vm().classesByName("com.coducation.smallbasic.ExprStmt").get(0)
								.fieldByName("lineno");
						Value lineno = ((ObjectReference) methodEvent.thread().frame(0).getArgumentValues().get(2))
								.getValue(linenoField); // eval 메소드의 3번째 파라미터
														// 가져오기
						lineNum = Integer.parseInt(lineno.toString());

					} catch (IncompatibleThreadStateException e1) {
						e1.printStackTrace();
					}

					// stop 할 위치 검사
					if (previousLineNum != lineNum && lineNum != 0 && (isStepState || breakPoints.contains(lineNum))) {
						
						HashMap<String, String> variableMap = new HashMap<>();
						
						// 변수정보 가져오기
						try {

							// Env.map (HashMap 타입) 참조 구하기
							Field mapField = jdiScript.vm().classesByName("com.coducation.smallbasic.Env").get(0)
									.fieldByName("map");
							Value map = ((ObjectReference) methodEvent.thread().frame(0).getArgumentValues().get(1))
									.getValue(mapField);

							// Env.label (HashMap 타입) 참조 구하기
							Field labelField = jdiScript.vm().classesByName("com.coducation.smallbasic.Env").get(0)
									.fieldByName("label");
							Value label = ((ObjectReference) methodEvent.thread().frame(0).getArgumentValues().get(1))
									.getValue(labelField);

							// MySmallBasic 변수값 가져오기
							try {
								// target_vm에서 사용할 메소드
								Method keySetMethod = jdiScript.vm().classesByName("java.util.HashMap").get(0)
										.methodsByName("keySet").get(0);
								Method iteratorMethod = jdiScript.vm().classesByName("java.util.Set").get(0)
										.methodsByName("iterator").get(0);
								Method hasNextMethod = jdiScript.vm().classesByName("java.util.Iterator").get(0)
										.methodsByName("hasNext").get(0);
								Method nextMethod = jdiScript.vm().classesByName("java.util.Iterator").get(0)
										.methodsByName("next").get(0);
								Method getMethod = jdiScript.vm().classesByName("java.util.HashMap").get(0)
										.methodsByName("get").get(0);

								List<Value> parameter = new LinkedList<Value>(); // 메소드의 파라미터
	

								// Env의 map을 가져와서 iterator로 접근
								Value keySet = ((ObjectReference) map).invokeMethod(methodEvent.thread(), keySetMethod,
										parameter, ObjectReference.INVOKE_SINGLE_THREADED);
								Value iterator = ((ObjectReference) keySet).invokeMethod(methodEvent.thread(),
										iteratorMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);
								Value hasNext = ((ObjectReference) iterator).invokeMethod(methodEvent.thread(),
										hasNextMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);

								// map의 모든 요소에 접근
								while (Boolean.parseBoolean(hasNext.toString())) {
									// key값
									Value key = ((ObjectReference) iterator).invokeMethod(methodEvent.thread(),
											nextMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);

									// Value 객체 구하기
									parameter.add(key);
									Value valueInstance = ((ObjectReference) map).invokeMethod(methodEvent.thread(),
											getMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);

									// Value.toString 구하기
									int startIdx = 12; // end index of "instance of"
									int endIdx = valueInstance.toString().indexOf("(id");
									String valueClass = valueInstance.toString().substring(startIdx, endIdx); // Value의 실제객체이름

									parameter.clear();
									Method toStringMethod = jdiScript.vm().classesByName(valueClass).get(0)
											.methodsByName("toString").get(0); // Value 실제 클래스의 toString
									Value value = ((ObjectReference) valueInstance).invokeMethod(methodEvent.thread(),
											toStringMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);

									// 구한 key와 value를 담기
									variableMap.put(key.toString(), value.toString());

									hasNext = ((ObjectReference) iterator).invokeMethod(methodEvent.thread(),
											hasNextMethod, parameter, ObjectReference.INVOKE_SINGLE_THREADED);
								}

							} catch (InvalidTypeException e) {
								e.printStackTrace();
							} catch (ClassNotLoadedException e) {
								e.printStackTrace();
							} catch (InvocationException e) {
								e.printStackTrace();
							}
						} catch (IncompatibleThreadStateException e) {
							e.printStackTrace();
						}

						debuggerClient.stopState(lineNum, variableMap);
					}
					previousLineNum = lineNum;
				});
	}

	// 디버거프로그램 시작
	public void run() {
		jdiScript.run();
	}

	// 한줄 진행
	public void step() {
		isStepState = true;
	}

	// 다음 breakpoint까지 진행
	public void continueDebugging() {
		isStepState = false;
	}

	// 디버거 종료
	public void exit() {
		jdiScript.vm().exit(0);
		debuggerClient.normalReturn();
	}
}
