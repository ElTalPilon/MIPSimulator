import java.io.File;
import java.io.FileNotFoundException;
import java.text.DecimalFormat;
import java.util.Scanner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Clase que simula un procesador de 1 n�cleo MIPS  
 */
//
public class MIPSimulator {
	private final int tamMemInstrucciones = 768;//768 / 4 = 192 instrucciones / 4 = 48 bloques
	private final int tamMemDatos = 832; 		//se asume que cada entero es de 4 bytes
	private final int numBloquesCache = 8;		//el cache tiene 8 bloques
	private final int DADDI = 8;
	private final int DADD 	= 32;
	private final int DSUB 	= 34;
	private final int DMUL 	= 12;
	private final int DDIV 	= 14;
	private final int LW 	= 35;
	private final int SW 	= 43;
	private final int FIN 	= 63;
	
	private Register[] R;		  // Registros del procesador
	private int[] instructionMem; // Memoria de instrucciones
	private int[] dataMem;        // Memoria de datos
	private Bloque[] cache;			//cache del mips, formada de 8 bloques de 16 enteros c/u
	
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
	
	//Constructor de la clase
	public MIPSimulator(){
		//se inicializan los registros
		R = new Register[32];
		for(int i=0; i<32; ++i){
			R[i] = new Register();
		}
		//se inicializa la memoria de instrucciones
		instructionMem = new int[tamMemInstrucciones];
		for(int i=0; i<tamMemInstrucciones; ++i){
			instructionMem[i] = 0;
		}
		//se inicializa la memoria de datos
		dataMem = new int[tamMemDatos];
		for(int i=0; i<tamMemDatos; ++i){
			dataMem[i] = 1;	//SE INICIALIZA EN 1 PARA SIMULAR QUE LOS CANDADOS
							//FUERON INICIADOS POR EL HILO PRINCIPAL
		}
		//se inicializa la cache
		cache = new Bloque[numBloquesCache];
		for(int i=0; i<numBloquesCache; ++i){
			cache[i] = new Bloque();
		}
		
		clock = new CyclicBarrier(4); // El 4 no s�...
		clockCycle = 0;
		PC = 0;
		runningID = true;
		
		IR = new int[4];
		
		//todos empezarian con vida
		idAlive = true;
		ifAlive = true;
		exAlive = true;
		memAlive = true;
		wbAlive = true;
	}
	
	/**
	 * Instancia de runnable que se encargar� de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucci�n a la que apunta PC y,
	 * si ID no est� ocupado, se le pasa IR.
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
				
				// Guarda la instrucci�n a ejecutar en IR
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
				// Espera a que ID se desocupe con un lock o algo as�
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
	 * En esta etapa se decodifica la instrucci�n, se obtienen la informaci�n
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
				
				boolean direccionValida;
				int bloqueMem, bloqueCache;
				switch(EX_MEM[2]){
					case LW:
						/*codigo viejo, sin implementacion de cache
						MEM_WB[0] = dataMem[EX_MEM[0]]; //
						MEM_WB[1] = EX_MEM[1];			//
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */
						
						//primero hay que verificar que la direccion que se tratar� de leer sea valida
						direccionValida = verificarDirMem(EX_MEM[0]);
						if(direccionValida){//si diera false, ser�a bueno agregar un manejo de excepcion
							//si fuera v�lida, hay que verificar que haya un hit de memoria en cache.
							//si diera fallo, hay que traer el bloque desde memoria si estuviera se toma directo de la cache
							bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
							bloqueCache = calcularBloqueCache(EX_MEM[0]);
							if(!hitMemoria(EX_MEM[0])){
								//si hubiera fallo de memoria y el bloque en cache correspondiente est� modificado,
								//primero hay que guardarlo a memoria. para esto primero calculamos el bloque en memoria
								//del dato que se quiere cargar. es aqui cuando se usa la estrategia WRITE ALLOCATE
								if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
									cacheStore(bloqueCache);
								}
								//en este punto se hace se carga la cache, pues no hubo hit de memoria
								//y si el bloque estaba modificado ya se guard� antes a memoria
								//es aqui cuando se usa la estrategia de WRITE BACK
								cacheLoad(EX_MEM[0]);
							}
							//en este punto hubo hit de memoria, por lo que se carga el dato desde la cache
							int indice = ((EX_MEM[0]+768) / 16 ) % 4;
							MEM_WB[0] = cache[bloqueCache].getBloque()[indice];
							MEM_WB[1] = EX_MEM[1];			//
							MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */			
						}//fin de if direccionValida
					break;
					
					case SW:
						/*codigo viejo, sin implementacion de cache
						dataMem[EX_MEM[0]] = EX_MEM[1]; //se hace el store en memoria
						MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
						MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
						MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion*/
						
						//nuevamente, lo primero es verificar que la referencia a memoria sea valida
						direccionValida = verificarDirMem(EX_MEM[0]);
						if(direccionValida){
							//si fuera valida, se verifica si el bloque de memoria est� en cache
							
						}
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
	 * @param program - Archivo .txt con lenguaje m�quina (para MIPS) en decimal. 
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
		for(int i = 0; i < dataMem.length; i+=10){
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
		for(int i = 0; i < instructionMem.length*4 && termino == false; i+=4){
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
		
		while(algunaEtapaViva() == true){
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
	
	//CORREGIDO. Es una mala practica tener varios return
	private boolean algunaEtapaViva(){
		//mientras alguna viva el principal seguira ejecutandose
		boolean algunaVive = false;
		if(ifAlive || idAlive || exAlive || memAlive || wbAlive){
			algunaVive = true;
		}
		return algunaVive;
	}
	
	//calcula el bloque de memoria en el que se encuentra una direccion de memoria espec�fica,
	//ej: la direccion de memoria 0003 est� en el bloque 000 de memoria, pues 3 / 16 = 0
	//	  la direccion de memoria 0017 est� en el bloque 001 de memoria, pues 17/ 16 = 1
	public int calcularBloqueMemoria(int dirMemoria){
		int posBloqueMem = (dirMemoria+768) / 16;
		return posBloqueMem;
	}
	
	//calcula el bloque de cache donde se debe almacenar un bloque de memoria espec�fico,
	//usando la estrategia de MAPEO DIRECTO
	//recibe: la direccion de memoria, a la cual se le calcular� su bloque de memoria
	//ej: la direccion 3324 est� en la direccion [831] del vector de memoria de datos,
	//    la direccion de meroria [831]  est� el bloque de memoria 255 y esta en el bloque de cache 7, pues 255 mod 8 = 1
	public int calcularBloqueCache(int dirMem){
		int bloqueMem = calcularBloqueMemoria(dirMem);
		int posBloqueCache = 0;
		posBloqueCache = bloqueMem % 8;
		return posBloqueCache;
	}
	
	//la memoria total es de 4096 enteros, 768 para instrucciones y 3328 para datos
	//como cada entero en la memoria de datos es de 4 bytes hay que hacer el calculo para accesar a la memoria de datos
	//as� las direcciones referenciables son de 768 a 3324, siendo 768 v[0], 772 v[1], ... 3324 v[831]
	//debe verificarse que las direcciones de memoria sean ser multiplos de 4 y que esten entre 768 y 3324
	//retorna true si la direccion es valida, de lo contrario devuelve false
	public boolean verificarDirMem(int dir){
		boolean valida=false;
		//primero verifica si la direccion es valida
		if((dir>767 && dir<3325) && (dir % 4 == 0 )){
			valida = true;
		}
		return valida;
	}
	
	//NO confundir con instruccion LOAD
	//guarda un bloque en cache, tra�do desde memoria
	//recibe la posicion de memoria en la cual esta el dato que se quiere cargar
	public void cacheLoad(int dirMemoria){
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(bloqueMem);
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 16 enteros
			cache[bloqueCache].setBloquePos(i, instructionMem[(bloqueMem*4)+i]);
		}
		cache[bloqueCache].setEtiqueta(bloqueMem);
	}//fin del metodo cacheLoad
	
	//NO confundir con instruccion STORE
	//guarda en memoria un bloque que est� en la cache de datos
	//recibe el numero de bloque de de cache que se quiere guardar (que basta para saber el bloque en memoria)
	public void cacheStore(int numBloqueCache){
		int dirMem = cache[numBloqueCache].getEtiqueta()*4 - 192; //direccion a partir de la que hay que guardar
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 4 enteros
			instructionMem[dirMem+i] = cache[numBloqueCache].getBloque()[i];
		}
	}//fin del metodo cacheStore
	
	//metodo para saber si un bloque de memoria est� guardado en la cache
	//recibe la direccion de memoria que se quiere accesar
	//devuelve true si est� en cache, de lo contrario devuleve false
	public boolean hitMemoria(int dirMemoria){
		boolean hit = false;
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(dirMemoria);
		if(cache[bloqueCache].getEtiqueta() == bloqueMem){
			hit = true;
		}
		return hit;
	}//fin del metodo hitMemoria
	
	//imprime el estado de TODAS las variables del mips
	void imprimirEstado(){
		//imprime el estado de los registros
		System.out.format("-----------------REGISTROS-----------------\n");
		int count=0;
		for(int i=0; i<8; ++i){
			for(int j=0; j<4; ++j){
				System.out.format("R%02d" + ": " + "%04d, ", count, R[i].get());
				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria de instrucciones
		System.out.println("\n--------MEMORIA DE INSTRUCCIONES--------");
		count=0;
		for(int i=0; i<48; ++i){
			System.out.format("Bloque%02d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", instructionMem[count]);
				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria de datos
		System.out.println("\n---------MEMORIA DE DATOS---------");
		count=0;
		for(int i=48; i<256; ++i){
			System.out.format("Bloque%03d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", dataMem[count]);
				++count;
			}
			System.out.println();
		}
		
		//imprime la memoria cache
		System.out.println("\n---------MEMORIA DE CACH�---------");
		count=0;
		for(int i=0; i<8; ++i){
			System.out.format("Bloque%02d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", cache[i].getBloque()[j]);
			}
			System.out.println();
		}
		
		//agregar middle stages, pc, IR, etc
		
	}//fin del metodo imprimirEstado
	
}//fin de la clase
