import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Clase que simula un procesador de 1 núcleo MIPS  
 */
//
public class MIPSimulator {
	private int[] instructionMem; // Memoria de instrucciones
	private int[] dataMem;        // Memoria de datos
	private Register[] R;		  // Registros del procesador
	private CyclicBarrier clock;  // Reloj del sistema
	private int clockCycle;       // Ciclo actual de reloj
	private int PC;               // Contador del programa / Puntero de instrucciones
	private boolean runningID;
	
	//en la ultima posicion se pasa el operation code
	private int[] IR;
	private int[] IF_ID = {-1,-1,-1,-1};
	private int[] ID_EX = {-1,-1,-1, -1};
	private int[] EX_MEM = {-1,-1, -1};
	private int[] MEM_WB = {-1,-1, -1};
	
	private final int DADDI = 8;
	private final int DADD = 32;
	private final int DSUB = 34;
	private final int DMUL = 12;
	private final int DDIV = 14;
	private final int LW = 35;
	private final int SW = 43;
	private final int FIN = 63;
	
	private int opCode = -1;
	
	//semaforos por cada etapa
	private static Semaphore semIf = new Semaphore(1);
	private static Semaphore semId = new Semaphore(1);
	private static Semaphore semEx = new Semaphore(1);
	private static Semaphore semMem = new Semaphore(1);
	private static Semaphore semR = new Semaphore(1);
	
	// Semaforo para controlar cada ciclo del reloj
	static CyclicBarrier barrier = new CyclicBarrier(6);
	
	//booleanos para saber si etapas estan vivas
	private boolean ifAlive;
	private boolean idAlive;
	private boolean exAlive;
	private boolean memAlive;
	private boolean wbAlive;
	
	/**
	 * Construye una instancia de MIPSimulator e inicializa sus atributos.
	 */
	public MIPSimulator(){
		PC = 0;
		IR = new int[4];
		//IF_ID = new int[4];		
		//ID_EX = new int[3];
		//EX_MEM = new int[2];
		//MEM_WB = new int[2];
		runningID = true;
		clock = new CyclicBarrier(4); // El 4 no sé...
		dataMem = new int[200];
		clockCycle = 0;
		instructionMem = new int[400];
		R = new Register[32];
		for(int i = 0; i < instructionMem.length; i++){
			instructionMem[i] = 0;
			dataMem[i%200] = 0;
			if(i < 32){
				R[i] = new Register();
			}
		}
		
		//todos empezarian con vida
		idAlive = true;
		ifAlive = true;
		exAlive = true;
		memAlive = true;
		wbAlive = true;
	}
	
	/**
	 * Instancia de runnable que se encargará de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucción a la que apunta PC y,
	 * si ID no está ocupado, se le pasa IR.
	 */
	private final Runnable IFstage = new Runnable(){
		@Override
		public void run(){
			while(ifAlive == true){
				
				if(IR[0] == FIN){
					ifAlive = false;
				}
				
				
				
				for(int i = 0; i < 4; ++i){
					IR[i] = instructionMem[PC+i];
				}
				
				// Guarda la instrucción a ejecutar en IR
				// hasta que ID este desocupado se ejecuta
				try {
					semIf.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				for(int i = 0; i < 4; ++i){
					IF_ID[i] = IR[i];
				}
				
				//*****
				System.out.println("ENTRO: IF_ID: " + IF_ID[0] +
						IF_ID[1] + IF_ID[2] + IF_ID[3]);
				// Aumenta el PC
				PC += 4;
				// Espera a que ID se desocupe con un lock o algo así
				semIf.release(1);
				
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
			}
		}
	};
	
	/**
	 * Instancia de runnable que se encargara de la etapa ID del pipeline.
	 * En esta etapa se decodifica la instrucción, se obtienen la información
	 * necesaria, ya sea en registros o en memoria de datos.
	 */
	private final Runnable IDstage = new Runnable(){
		@Override
		public void run(){
			while(idAlive == true){
				if(IF_ID[0] == FIN){
					idAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				//y desbloquea la etapa hacia atras
				if(IF_ID[0] == -1){
					semIf.release();
					continue;
					
				}
				//*****
				System.out.println("ENtro ID");
				try {
					semId.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				try {
					semR.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				switch(IF_ID[0]){
				case DADDI:
					ID_EX[0] = R[IF_ID[1]].get(); // RY
					ID_EX[1] = IF_ID[2];          // X
					ID_EX[2] = IF_ID[3];          // n
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DADD:
					ID_EX[0] = R[IF_ID[1]].get(); // Reg Operando1
					ID_EX[1] = R[IF_ID[2]].get(); // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DSUB:
					ID_EX[0] = R[IF_ID[1]].get(); // Reg Operando1
					ID_EX[1] = R[IF_ID[2]].get(); // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DMUL:
					ID_EX[0] = R[IF_ID[1]].get(); // Reg Operando1
					ID_EX[1] = R[IF_ID[2]].get(); // Reg Operando2
					ID_EX[2] = IF_ID[3];          // Reg Operando3
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case DDIV:
					ID_EX[0] = R[IF_ID[1]].get();	// Reg Operando1
					ID_EX[1] = R[IF_ID[2]].get();	// Reg Operando2
					ID_EX[2] = IF_ID[3];			// Reg Operando3
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case LW:
					ID_EX[0] = IF_ID[3]; 			// valor inmediato
					ID_EX[1] = R[IF_ID[1]].get();  // Origen
					ID_EX[2] = IF_ID[2];          	// Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
				case SW:
					ID_EX[0] = IF_ID[3]; 			// valor inmediato
					ID_EX[1] = R[IF_ID[2]].get(); 	// Origen
					ID_EX[2] = R[IF_ID[1]].get();	// Destino
					ID_EX[3] = IF_ID[0];			//operation code
				break;
			  }//fin del switch
				semId.release();
				semIf.release();
				semR.release();
				//*****
				System.out.println("ENTRO: ID_EX: " + ID_EX[0] +
						ID_EX[1] + ID_EX[2] + ID_EX[3]);
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}
			//runningID = false;	
		}
	};
	
	/** 
	 * En esta etapa se escribe en EX_M
	 * debe verificar que EX_M no este bloqueado
	 */
	private final Runnable EXstage = new Runnable(){
		@Override
		public void run(){
			
			while(exAlive == true){
				
				if(ID_EX[3] == FIN){
					exAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				//y desbloquea la etapa hacia atras
				if(ID_EX[3] == -1){
					semId.release();
					continue;
				}
				
				try {
					semEx.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				switch(ID_EX[3]){
					case DADDI:
						EX_MEM[0] = ID_EX[0]+ID_EX[2];
						EX_MEM[1] = ID_EX[1]; //como escribe en registro, el campo de memoria va vacio
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DADD:
						EX_MEM[0] = ID_EX[0] + ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DSUB:
						EX_MEM[0] = ID_EX[0] - ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DMUL:
						EX_MEM[0] = ID_EX[0] * ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case DDIV:
						EX_MEM[0] = ID_EX[0] / ID_EX[1];
						EX_MEM[1] = ID_EX[2];
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case LW:
						EX_MEM[0] = ID_EX[0]+ID_EX[1];	//Resultado de memoria del ALU
						EX_MEM[1] = ID_EX[2];			//Destino
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
					case SW:
						EX_MEM[0] = ID_EX[0]+ID_EX[2];	//Resultado de memoria del ALU
						EX_MEM[1] = ID_EX[1];			//Destino
						EX_MEM[2] = ID_EX[3]; //codigo de operacion
					break;
				}//fin del switch
				semEx.release();
				semId.release();
				
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				}//fin del while
			}
			
	};
	
	//En esta etapa se escribe en MEM_WB
	//
	private final Runnable MEMstage = new Runnable(){
		@Override
		public void run(){
			
			while(memAlive == true){
				
				if(EX_MEM[2] == FIN){
					memAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				if(EX_MEM[2] == -1){
					semEx.release();
					continue;
				}
				
				try {
					semMem.acquire();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				switch(EX_MEM[2]){
					case LW:
						MEM_WB[0] = dataMem[EX_MEM[0]]; //
						MEM_WB[1] = EX_MEM[1];			//
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion
					break;
					case SW:
						dataMem[EX_MEM[0]] = EX_MEM[1]; //se hace el store en memoria
						MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
						MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion
					break;
					default:
						MEM_WB[0] = EX_MEM[0];
						MEM_WB[1] = EX_MEM[1];
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion
				}//fin del switch
				
				semMem.release();
				semEx.release();
				
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
			}//fin del while
			
		}//fin del run
	};
	
	private final Runnable WBstage = new Runnable(){
		@Override
		public void run(){
			
			while(wbAlive == true){
				
				if(MEM_WB[2] == FIN){
					wbAlive = false;
				}
				
				//Si es la primera instruccion no hace nada
				if(EX_MEM[2] == -1){
					semMem.release();
					continue;
				}
				
				switch(MEM_WB[2]){
					case DADDI:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					case DADD:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					case DSUB:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					case DMUL:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					case DDIV:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					case LW:
						R[MEM_WB[1]].set(MEM_WB[0]);
					break;
					//en RW no hace nada en la etapa de writeback
				}//fin del switch
				
				semMem.release(1);
				semR.release();
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}// fin del while
			
		}
		
	};
	
	/**
	 * Ejecuta el programa especificado.
	 * @param program - Archivo .txt con lenguaje máquina (para MIPS) en decimal. 
	 */
	public void loadFile(File program){
		try{
			// Guarda las instrucciones del archivo en la memoria
			Scanner scanner = new Scanner(program);
			for(int i = 0; i < instructionMem.length; i++){
				if(scanner.hasNext()){
					instructionMem[i] = scanner.nextInt();
				}else{
					instructionMem[i] = -1;
				}
			}
			scanner.close();
		}catch(FileNotFoundException e){
			System.err.println("Error abriendo el archivo del programa.");
		}
		// Imprime el programa que se va a ejecutar
		printProgram();
	}	
	
	/**
	 * Despliega en consola el estado actual de:
	 *   - Ciclos de reloj ejecutados.
	 *   - Contenido en los registros.
	 *   - Contenido en la memoria de datos.
	 */
	public void printState(){
		System.out.println("Ciclos ejecutados: " + clockCycle);
		System.out.println("\n+++++CONTENIDO EN REGISTROS+++++");
		for(int i = 0; i < R.length; i++){
			System.out.println("R" + i + ": " + R[i].get() + " ");
		}
		System.out.println("\n+++++CONTENIDO EN MEMORIA+++++");
		for(int i = 0; i < 200; i+=10){
			for(int j = 0; j < 10; j++){
				System.out.print(dataMem[i+j] + " ");
			}
			System.out.println();
		}
		
		/*System.out.print("\nPC: " + PC + "\n");
		
		System.out.print("IR: ");
		for(int i=0; i<4; ++i){
			System.out.print(IR[i] + ", ");
		}
		System.out.println();
		
		System.out.println("\nIF_ID: " + IF_ID[0] + ", " + IF_ID[1] + ", " + IF_ID[2] + ", " + IF_ID[3]);
		System.out.println("ID_EX: " + ID_EX[0] + ", " + ID_EX[1] + ", " + ID_EX[2]);
		System.out.println("EX_MEM: " + EX_MEM[0] + ", " + EX_MEM[1]);
		System.out.println("MEM_WB: " + MEM_WB[0] + ", " + MEM_WB[1]);*/
	}
	
	/**
	 * Despliega el contenido de la memoria de instrucciones,
	 * es decir, el programa a ejecutar.
	 */
	public void printProgram(){
		boolean termino = false;
		System.out.println("+++++PROGRAMA A EJECUTAR+++++");
		for(int i = 0; i < 400 && termino == false; i+=4){
			for(int j = 0; j < 4; j++){
				if(instructionMem[i+j] == -1){
					termino = true;
				}
				else{
					System.out.print(instructionMem[i+j] + " ");
				}
			}
			System.out.println();
		}
	}

	public void iniciarSemaforos(){
		semId.drainPermits();
		semIf.drainPermits();
		semMem.drainPermits();
		semEx.drainPermits();
	}
	
	public void runProgram() {
		
		iniciarSemaforos();
		Thread IF = new Thread(IFstage);
		Thread ID = new Thread(IDstage);
		Thread EX = new Thread(EXstage);
		Thread M  = new Thread(MEMstage);
		Thread WB = new Thread(WBstage);
		IF.start();
		ID.start();
		EX.start();
		M.start();
		WB.start();
		
		while(etapasVivas() == true){
			try {
				
				// esperar que se ejecuten todos y entrar
				barrier.await();
				//vuelve a iniciar los semaforos, es decir bloquea los intermedios
				iniciarSemaforos();
				
				// bloquea los registros
				semR.acquire();
				barrier.await();
				printState();
				
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		printState();
		
	}
	
	
	private boolean etapasVivas(){
		//mientras alguna viva el principal seguira
		// ejecutandose
		if(ifAlive || idAlive || exAlive || memAlive || wbAlive){
			return true;
		}
		return false;
	}
}
	
	
	