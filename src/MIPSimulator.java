import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


import javax.swing.JOptionPane;

/**
 * Clase que simula un procesador de 1 núcleo MIPS  
 */
//
public class MIPSimulator {
	private final int tamMemInstrucciones = 768;//768 / 4 = 192 instrucciones / 4 = 48 bloques
	private final int tamMemDatos = 832; 		//se asume que cada entero es de 4 bytes
	private final int numBloquesCache = 8;		//el cache tiene 8 bloques
	private int primerCampoVacio = 0;     		//Desde donde se puede cargar el siguiente hilo

	// Constantes para los codigos de operacion
	private final int DADDI = 8;
	private final int DADD 	= 32;
	private final int DSUB 	= 34;
	private final int DMUL 	= 12;
	private final int DDIV 	= 14;
	private final int LW 	= 35;
	private final int SW 	= 43;
	private final int FIN 	= 63;
	private final int LL 	= 50;
	private final int SC	= 51;

	private int linkRegister;
	private int[] R;		  		// Registros del procesador
	private int [] rUsados; 		//Registros que estan en uso para evitar conflicto de datos
	private int[] instructionMem;	// Memoria de instrucciones
	private int[] dataMem;        	// Memoria de datos
	private Bloque[] cache;			//cache del mips, formada de 8 bloques de 16 enteros c/u

	private static int clock;	// Reloj del sistema
	private int PC;				// Contador del programa / Puntero de instrucciones
	int quantum;				// El quatum para implementar el round round robin


	//en la ultima posicion se pasa el operation code
	private int[] IR;
	private int[] IF_ID = {-1,-1,-1,-1};
	private int[] ID_EX = {-1,-1,-1, -1};
	private int[] EX_MEM = {-1,-1, -1};
	private int[] MEM_WB = {-1,-1, -1};



	//semaforos por cada etapa
	private static Semaphore semIf = new Semaphore(1);
	private static Semaphore semId = new Semaphore(1);
	private static Semaphore semEx = new Semaphore(1);
	private static Semaphore semMem = new Semaphore(1);
	private static Semaphore semR = new Semaphore(1);

	// Barrera para controlar cada ciclo del reloj
	static CyclicBarrier barrier = new CyclicBarrier(6);

	// Booleanos para saber si etapas estan vivas
	private boolean ifAlive;
	private boolean idAlive;
	private boolean exAlive;
	private boolean memAlive;
	private boolean wbAlive;

	private boolean hayConflicto = false; // indica a IF que hay conflicto de datos

	/**
	 * Constructor de la clase, recibe del usuario el quantum con el
	 * que se va a trabajar el round Robin
	 * @param quantum
	 */
	public MIPSimulator(int quantum){
		this.quantum = quantum;

		// Se inicializan los registros en 0
		R = new int[32];
		rUsados = new int[32];
		for(int i=0; i<32; ++i){
			R[i] = 0;
			rUsados[i] = 0;
		}
		// Se inicializa la memoria de instrucciones
		instructionMem = new int[tamMemInstrucciones];
		for(int i=0; i<tamMemInstrucciones; ++i){
			instructionMem[i] = 1;
		}
		// Se inicializa la memoria de datos
		dataMem = new int[tamMemDatos];
		for(int i=0; i<tamMemDatos; ++i){
			dataMem[i] = 1;	//SE INICIALIZA EN 1 PARA SIMULAR QUE LOS CANDADOS
			//FUERON INICIADOS POR EL HILO PRINCIPAL
		}
		// Se inicializa la cache
		cache = new Bloque[numBloquesCache];
		for(int i=0; i<numBloquesCache; ++i){
			cache[i] = new Bloque();
		}

		// Inicializar valores del ciclo del reloj y PC en 0
		clock = 0;
		PC = 0;

		// Inicializar IR
		IR = new int[4];

		linkRegister = -1;	//por comodidad se inicializa en -1

		// Todos los hilos iniciaran con vida
		idAlive = true;
		ifAlive = true;
		exAlive = true;
		memAlive = true;
		wbAlive = true;
	}

	/**
	 * Instancia de runnable que se encargará de la etapa IF del pipeline.
	 * En esta etapa se guarda en IR la instrucción a la que apunta PC y,
	 * cuando ID ya no este ocupado se le pasa la instruccion en IF_ID
	 */
	private final Runnable IFstage = new Runnable(){
		@Override
		public void run(){
			// Mientras no se llegue a la instruccion 63 la etapa ejecutara lo que le corresponde
			while(ifAlive == true){

				/* Obtiene la instruccion del vector de instrucciones esto lo hara en paralelo
				 * con las otras etapas, se sincroniza a la hora de que pasa la instrucción a ID */
				for(int i = 0; i < 4; ++i){
					IR[i] = instructionMem[PC+i];
				}


				/* Cuando IF termina  le mandara la instruccion 63 a ID
				 * pone la bandera de que esta viva en falso y hara el await para que el hilo
				 * principal haga cambio de ciclo */
				if(IR[0] == FIN){
					ifAlive = false;
				}

				/* Hasta que ID este desocupado se ejecuta, pide permiso para ingresar a esta
				 * region de codigo */
				try {
					semIf.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				/* Si hay conflicto IF no hace nada pero se actualiza el reloj  
				 * no va a pasar una nueva instruccion hasta que termine el conflicto */
				if(hayConflicto){
					semIf.release(1);

					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;
				}

				// Guarda la instrucción a ejecutar en el registro intermedio
				for(int i = 0; i < 4; ++i){
					IF_ID[i] = IR[i];
				}

				//***** Probar valores en registro intermedio
				System.out.println("ENTRO: IF_ID: " + IF_ID[0] +
						IF_ID[1] + IF_ID[2] + IF_ID[3]);

				// Aumenta el PC
				PC += 4;

				semIf.release(1);

				/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
				 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
				 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
				 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
				 * principal que las controla */
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}
			}//fin de while(alive)

			/* Muere y actualiza la barrera para controlar  el ciclo del reloj.
			 * Cuando IF deja de mandar instrucciones, actualiza el barrier que el principal tiene
			 * para actualizar el ciclo de reloj, hasta que WB muera*/
			while (wbAlive) {
				try {
					barrier.await(5,TimeUnit.SECONDS);
					barrier.await(5,TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					continue;
				} catch (BrokenBarrierException e) {
					continue;
				} catch (TimeoutException e) {
					continue;
				}
			}

		}//fin de metodo run
	};//fin de metodo IFstage

	/**
	 * Instancia de runnable que se encargara de la etapa ID del pipeline.
	 * En esta etapa se decodifica la instrucción, se obtienen la información
	 * necesaria, ya sea en registros o en memoria de datos.
	 */
	private final Runnable IDstage = new Runnable(){
		// operadores para almacenar lo que se lee de IF_ID
		int operador_0; // Codigo de operacion
		int operador_1; // Operador
		int operador_2; // operador
		int operador_3; // operador
		@Override
		public void run(){
			// Mientras no se llegue a la instruccion 63 la etapa ejecutara lo que le corresponde
			while(idAlive == true){
				if(IF_ID[0] == FIN){
					idAlive = false;
				}

				/*Se leen los registros entre etapas a la izquierda al 
				 * inicio del ciclo
				 */
				operador_0 = IF_ID[0];
				operador_1 = IF_ID[1];
				operador_2 = IF_ID[2];
				operador_3 = IF_ID[3];

				//Si es la primera instruccion no hace nada
				//y desbloquea la etapa hacia atras
				// aunque no haga nada tiene que actualizar el barrier
				// para que se actualice el ciclo del reloj
				if(operador_0 == -1){
					semIf.release();

					/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
					 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
					 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
					 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
					 * principal que las controla */
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;

				}

				/* Para escribir en registro y mandar los valores en ID_EX
				 * pregunta a EX si esta ocupado (Desbloqueo hacia atras)
				 */
				try {
					semId.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				/*
				 * Pide permiso para bloquear los registros y usarlos
				 */
				try {
					semR.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				/***********************************************
				 *  Codigo para el manejo de conflicto de datos
				 *  Si el registro que se requiere como operando aun no ha sido escrito
				 *  por writeBack  ID se queda esperando hasta que WB escriba en el registro
				 *  destino, manda una burbuja a ID_EX si hay conflicto de datos.
				 *  Para verificar utiliza la tabla de registros usados, si hay conflicto ademas
				 *  desbloquea if y le avisa con una bandera que hay conflicto y aun no le pase la
				 *  siguiente instruccion
				 * ********************************************
				 */
				//if()if(hayConflicto == false){

				switch(operador_0){//contiene el código de instruccion
				case DADDI:
					if(rUsados[operador_1] == 1 ){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					} // fin if

					break;
				case DADD:
					if( (rUsados[operador_1] == 1) || (rUsados[operador_2] == 1) ){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					} // fin if	
					break;
				case DSUB:
					if((rUsados[operador_1] == 1) || (rUsados[operador_2] == 1)){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					} // Fin if


					break;
				case DMUL:
					if( (rUsados[operador_1] == 1) || (rUsados[operador_2] == 1) ){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale


						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					}//fin if

					break;
				case DDIV:
					if( (rUsados[operador_1] == 1) || (rUsados[operador_2] == 1) ){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					}// fin if

					break;
				case LW:
					if( (rUsados[operador_1] == 1) ){
						ID_EX[3] = -1;			//operation code

						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					} // fin if

					break;
				case SW:
					if((rUsados[operador_2] == 1)  || (rUsados[operador_1] == 1) ){
						ID_EX[3] = -1;			//operation code
						System.out.println("ENTRO ACA");
						hayConflicto = true;
						semIf.release();
						semR.release();
						//actualiza el reloj y se sale
						try {
							barrier.await();
							barrier.await();
						} catch (InterruptedException e) {
							e.printStackTrace();
						} catch (BrokenBarrierException e) {
							e.printStackTrace();
						}
						continue;	
					} // fin if
					break;
				case LL:

					break;
				case SC:

					break;

				case FIN:
					break;

				}//fin del switch

				//}

				/***********************************************
				 *  Fin manejo de conflicto de datos
				 * ********************************************
				 */

				/* si salio del switch quiere decir que no hay 
				 * conflicto y escribe en ID_EX 
				 */
				hayConflicto = false;
				switch(operador_0){//contiene el código de instruccion
				case DADDI:
					ID_EX[0] = R[operador_1]; // RY
					ID_EX[1] = operador_2;          // Destino
					ID_EX[2] = operador_3;          // n
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_2] = 1;  // guarda en la tabla que registro destino esta siendo usado
					break;
				case DADD:
					ID_EX[0] = R[operador_1]; // Reg Operando1
					ID_EX[1] = R[operador_2]; // Reg Operando2
					ID_EX[2] = operador_3;          // Reg Destino
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_3] = 1; 
					break;
				case DSUB:
					ID_EX[0] = R[operador_1]; // Reg Operando1
					ID_EX[1] = R[operador_2]; // Reg Operando2
					ID_EX[2] = operador_3;          // Reg Destino
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_3] = 1; 
					break;
				case DMUL:
					ID_EX[0] = R[operador_1]; // Reg Operando1
					ID_EX[1] = R[operador_2]; // Reg Operando2
					ID_EX[2] = operador_3;          // Reg destino
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_3] = 1; 
					break;
				case DDIV:
					ID_EX[0] = R[operador_1];			// Reg Operando1
					ID_EX[1] = R[operador_2];			// Reg Operando2
					ID_EX[2] = operador_3;			// Reg Destino
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_3] = 1; 
					break;
				case LW:
					ID_EX[0] = operador_3; 			// valor inmediato
					ID_EX[1] = R[operador_1];  		// Origen
					ID_EX[2] = operador_2;          	// Destino
					ID_EX[3] = operador_0;			//operation code
					rUsados[operador_2] = 1; 
					break;
				case SW:
					// No hay que ponerle registros usados por que se escribira en
					// memoria
					ID_EX[0] = operador_3; 			// valor inmediato
					ID_EX[1] = R[operador_2]; 	// Origen
					ID_EX[2] = R[operador_1];	// Destino
					ID_EX[3] = operador_0;			//operation code
					break;
				case LL:

					break;
				case SC:

					break;

				case FIN:
					ID_EX[0] = 0; 			// valor inmediato
					ID_EX[1] = 0; 	// Origen
					ID_EX[2] = 0;	// Destino
					ID_EX[3] = operador_0;			//operation code
					break;

				}//fin del switch


				semId.release();
				// Avisa que no hay conflicto para que IF ya lea otra instruccion
				// luego la desbloquea
				hayConflicto = false;
				// Le dice a IF que ya se desocupo y desbloquea los registros
				semIf.release();
				semR.release();

				//*****
				System.out.println("ENTRO: ID_EX: " + ID_EX[3] +
						ID_EX[2] + ID_EX[0] + ID_EX[1]);

				/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
				 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
				 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
				 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
				 * principal que las controla */
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}

			}//fin del while alive

			/* Muere y actualiza la barrera para controlar  el ciclo del reloj.
			 * Cuando ID deja de decodificar instrucciones, actualiza el barrier que el principal 
			 * tiene para actualizar el ciclo de reloj, hasta que WB muera */
			while (wbAlive) {
				try {
					barrier.await(5,TimeUnit.SECONDS);
					barrier.await(5,TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					continue;
				} catch (BrokenBarrierException e) {
					continue;
				} catch (TimeoutException e) {
					continue;
				}
			}

		}//fin del metodo run
	};//fin del metodo IDstage

	/** 
	 * En esta etapa se realizan las operaciones aritmeticas
	 * se escribe en EX_M , debe verificar que EX_M no este bloqueado
	 */
	private final Runnable EXstage = new Runnable(){
		// operadores para almacenar lo que se lee de ID_EX
		int operador_0; // Fuente
		int operador_1; // Fuente
		int operador_2; // Destino
		int operador_3; // Codigo de operacion
		@Override
		public void run(){
			// Mientras no se llegue a la instruccion 63 la etapa ejecutara lo que le corresponde
			while(exAlive == true){

				/*Se leen los registros entre etapas a la izquierda al 
				 * inicio del ciclo
				 */
				operador_0 = ID_EX[0];
				operador_1 = ID_EX[1];
				operador_2 = ID_EX[2];
				operador_3 = ID_EX[3];

				/* Si el codigo de operacion es 63 debe morir cuando WB muera*/
				if(operador_3 == FIN){
					exAlive = false;
				}

				/*Si es la primera instruccion no hace nada y desbloquea la etapa hacia atras  
				 * actualiza el ciclo del reloj
				 */
				if(operador_3 == -1){
					semId.release();	

					// Manda -1 a mem
					EX_MEM[0] = -1;
					EX_MEM[1] = -1;
					EX_MEM[2] = -1;

					/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
					 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
					 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
					 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
					 * principal que las controla */
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}

					continue;
				} // fin if cuando esta comenzando las etapas

				/* Si Mem Stage lo desbloqueo pasa el resultado de las operaciones aritmeticas
				 * y el registro destino o posicion de memoria a EX_MEM
				 */
				try {
					semEx.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				switch(operador_3){
				case DADDI:
					EX_MEM[0] = operador_0 + operador_2;
					EX_MEM[1] = operador_1; //como escribe en registro, el campo de memoria va vacio
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case DADD:
					EX_MEM[0] = operador_0 + operador_1;
					EX_MEM[1] = operador_2;
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case DSUB:
					EX_MEM[0] = operador_0 - operador_1;
					EX_MEM[1] = operador_2;
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case DMUL:
					EX_MEM[0] = operador_0 * operador_1;
					EX_MEM[1] = operador_2;
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case DDIV:
					EX_MEM[0] = operador_0 / operador_1;
					EX_MEM[1] = operador_2;
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case LW:
					EX_MEM[0] = operador_0 + operador_1;	//Resultado de memoria del ALU
					EX_MEM[1] = operador_2;			//Destino
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case SW:
					EX_MEM[0] = operador_0 + operador_2;	//Resultado de memoria del ALU
					EX_MEM[1] = operador_1;			//Destino
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				case FIN:
					EX_MEM[0] = 0;	//Resultado de memoria del ALU
					EX_MEM[1] = 0;			//Destino
					EX_MEM[2] = operador_3; //codigo de operacion
					break;
				}//fin del switch
				semEx.release();

				// Le dice a ID que no esta ocupado
				semId.release();
				//***** PRUEBA
				System.out.println("ENTRO EX_MEM: " + EX_MEM[2] +
						EX_MEM[1] + EX_MEM[0] );

				/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
				 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
				 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
				 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
				 * principal que las controla */
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}

			}//fin del while alive

			/* Muere y actualiza la barrera para controlar  el ciclo del reloj.
			 * Cuando EX deja de ejecutar operaciones en el ALU, actualiza el barrier que el principal tiene
			 * para actualizar el ciclo de reloj, hasta que WB muera */
			while (wbAlive) {
				try {
					barrier.await(5,TimeUnit.SECONDS);
					barrier.await(5,TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					continue;
				} catch (BrokenBarrierException e) {
					continue;
				} catch (TimeoutException e) {
				}
			}

		}//fin del metodo run
	};//fin del metodo EXstage

	/**
	 * En esta etapa se carga de memoria o se almacena en memoria, es decir
	 * se realiza el store, o se obtiene el valor de memoria que se desea almacenar
	 * en algun registro. Se verifica los hit de cache y todas las operaciones
	 * que se realizan con la cache
	 */
	private final Runnable MEMstage = new Runnable(){
		// operadores para almacenar lo que se lee de EX_MEM
		int operador_0; // Resultado operacion ALU
		int operador_1; // Destino
		int operador_2; // Codigo de operacion

		@Override
		public void run(){
			// Mientras no se llegue a la instruccion 63 la etapa ejecutara lo que le corresponde
			while(memAlive == true){

				/*Se leen los registros entre etapas a la izquierda al 
				 * inicio del ciclo
				 */
				operador_0 = EX_MEM[0];
				operador_1 = EX_MEM[1];
				operador_2 = EX_MEM[2];

				// Si el codigo de operacion es 63
				// la etapa tiene que termiar-morir
				if(operador_2 == FIN){
					memAlive = false;
				}

				/*Si es la primera instruccion no hace nada libera a EX y actualiza el reloj 
				 * En los registros intermedios con Mem pone un -1
				 */
				if(operador_2 == -1){
					semEx.release();
					MEM_WB[2] = -1;

					/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
					 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
					 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
					 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
					 * principal que las controla */
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					}
					continue;
				}

				// Pregunta a WriteBack si ya se desocupo
				try {
					semMem.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				boolean direccionValida;
				int bloqueMem, bloqueCache;
				// TODO: VOLVER A PONER LO DE CACHE
				switch(EX_MEM[2]){
				case LW: 
					MEM_WB[0] = dataMem[operador_0]; //
					MEM_WB[1] = operador_1;			//
					MEM_WB[2] = operador_2; 			//codigo de operacion 
					break;
				case SW:
					dataMem[operador_0] = operador_1; //se hace el store en memoria
					MEM_WB[0] = operador_0;			//no hace nada pero tiene que pasar el valor
					MEM_WB[1] = operador_1;			//no hace nada pero tiene que pasar el valor
					MEM_WB[2] = operador_2; 			//codigo de operacion
					break;

					// aqui se maneja el caso de que sea otra operacion
					// resuelta en EX o sea codigo de operacion FIN
				default:
					MEM_WB[0] = operador_0;
					MEM_WB[1] = operador_1;
					MEM_WB[2] = operador_2; 			//codigo de operacion
				}//fin del switch

				semMem.release();
				semEx.release();

				//*****PRUEBA
				System.out.println("ENTRO MEM_WB: " + MEM_WB[2] +
						MEM_WB[1] + MEM_WB[0] );

				/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
				 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
				 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
				 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
				 * principal que las controla */
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (BrokenBarrierException e) {
					e.printStackTrace();
				}


			}//fin del while

			/* Muere y actualiza la barrera para controlar  el ciclo del reloj.
			 * Actualiza el barrier que el principal tiene
			 * para actualizar el ciclo de reloj, hasta que WB muera 
			 * */
			while (wbAlive) {
				try {
					barrier.await(5,TimeUnit.SECONDS);
					barrier.await(5,TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					continue;
				} catch (BrokenBarrierException e) {
					continue;
				} catch (TimeoutException e) {
					continue;
				}
			}

		}//fin del metodo run
	};//fin del metodo MEMstage

	/**
	 * Etapa que escribe en en los registros destinos el valor calculado en EX
	 * Libera a MEM y además libera los registros
	 */
	private final Runnable WBstage = new Runnable(){
		// operadores para almacenar lo que se lee de MEM_WB
		int operador_0; // resultado operacion
		int operador_1; // Destino
		int operador_2; // codigo de operacion

		@Override
		public void run(){

			// Mientras no se llegue a la instruccion 63 la etapa ejecutara lo que le corresponde
			while(wbAlive == true){

				/*Se leen los registros entre etapas a la izquierda al 
				 * inicio del ciclo
				 */
				operador_0 = MEM_WB[0]; 
				operador_1 = MEM_WB[1]; 
				operador_2 = MEM_WB[2]; 

				/* Cuando recibe un 63 de código de operacion no hace nada  solo actualiza el ciclo 
				 * del reloj y avisa a las etapas que murio mediante la bandera wbAlive */
				if(operador_2 == FIN){

					/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
					 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
					 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
					 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
					 * principal que las controla */
					try {
						barrier.await(5,TimeUnit.SECONDS);
						barrier.await(5,TimeUnit.SECONDS);
					} catch (InterruptedException e) {
						e.printStackTrace();
					} catch (BrokenBarrierException e) {
						e.printStackTrace();
					} catch (TimeoutException e) {
					}
					wbAlive = false;

					continue;
				}

				// Si es la primera instruccion no hace nada
				// libera registros y Mem Stage y actualiza el reloj
				if(operador_2 == -1){
					semMem.release();
					semR.release();
					try {
						barrier.await();
						barrier.await();
					} catch (InterruptedException e) {
						break;
					} catch (BrokenBarrierException e) {
						break;
					}
					continue;
				}

				/* Se escribe en el registro y además se actualiza la tabla de registros usados
				 * restando el que actualmente fue escrito
				 */
				switch(operador_2){
				case DADDI:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
				case DADD:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
				case DSUB:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
				case DMUL:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
				case DDIV:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
				case LW:
					R[operador_1] = operador_0;
					rUsados[operador_1] = 0;  // una vez que escribe en el registro notifica que no habra conflicto
					break;
					//en SW no hace nada en la etapa de writeback
				}//fin del switch

				// imprimir registros usados para probar
				System.out.format("-----------------REGISTROS USADOS WB-----------------\n");
				int count=0;
				for(int i=0; i<8; ++i){
					for(int j=0; j<4; ++j){
						System.out.format("R%02d" + ": " + "%04d, ", count, rUsados[count]);

						++count;
					}
					System.out.println();
				}

				// Le dice a la etapa MEM que ya esta libre y libera registros
				semMem.release(1);
				semR.release();

				/* Con este barrier que el hilo principal controla se actualiza el ciclo del reloj
				 * El primer await sera para decir que termino de realizar operaciones por el ciclo actual 
				 * y el segundo await es para saber que se volvera a empezar un nuevo ciclo de reloj 
				 * corriendo las etapas en paralelos hasta que todas hagan ese await, incluido el hilo 
				 * principal que las controla */
				try {
					barrier.await();
					barrier.await();
				} catch (InterruptedException e) {
					break;
				} catch (BrokenBarrierException e) {
					break;
				}

			}// fin del while	


		}//fin del metodo run
	};//fin del metodo WBstage

	/**
	 * Guarda las instrucciones del archivo especificado 
	 * en la memoria de instrucciones.Si recibe mas de un hilo
	 * lo guardara en la posicion del vector de instrucciones contigua 
	 * a la intruccion 63 del hilo anterior
	 * @param program - Archivo .txt con lenguaje máquina (para MIPS) en decimal. 
	 */
	public boolean loadFile(File program){
		int pos = primerCampoVacio;
		boolean sePudo = false;
		try{
			Scanner scanner = new Scanner(program);
			while(scanner.hasNext() && pos < tamMemInstrucciones/4){
				instructionMem[pos] = scanner.nextInt();
				pos++;
			}

			// Se fija si el hilo cupo en la memoria
			if(scanner.hasNext()){
				// De no ser así, borra lo que haya escrito
				for(int i = primerCampoVacio; i < tamMemInstrucciones; i++){
					instructionMem[i] = 1;
				}
			}
			else{
				primerCampoVacio = pos;
				sePudo = true;
			}
			scanner.close();
		}catch(FileNotFoundException e){
			System.err.println("Error abriendo el archivo del programa.");
		}
		return sePudo;
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

	/**
	 * Metodo que se llama al inicio de cada ciclo nuevo de reloj, con el fin
	 * de que se bloque todo, este metodo sera usado desde el principal
	 */
	public void iniciarSemaforos(){
		semId.drainPermits();
		semIf.drainPermits();
		semMem.drainPermits();
		semEx.drainPermits();
	}

	/**
	 * Metodo que corresponde al hilo principal, en donde se controlara 
	 * los ciclos de reloj, el cambio de contexto, el bloqueo al inicio de las etapas
	 * e imprimira resultados finales
	 */
	public void runProgram() {

		/*
		 * Iniciar  bloqueo de semaforos
		 * para la primera ejecucion de las etapas
		 * e inica cada thread correspondiente a cada 
		 * etapa
		 */
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

		/*
		 * Mientras alguna alguna etapa este viva correra todo para actualizar el reloj
		 * y realizar las operaciones necesarias
		 */
		while(algunaEtapaViva() == true){
			try {

				/* espera que se ejecuten todas las etapas 
				 * para actualizar el ciclo del reloj
				 */

				barrier.await(5,TimeUnit.SECONDS);

				//vuelve a iniciar los semaforos, es decir bloquea los intermedios
				iniciarSemaforos();

				// bloquea los registros
				try{// bloquea los registros
					semR.acquire();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				/* Actualiza el ciclo del reloj al terminar todas las etapas
				 * de realizar sus respectivas acciones
				 */

				clock++;
				// TODO: AQUI VA DONDE COMPARA CUANTOS CLOCKS LLEVA EL HILO CON EL QUAUNTUM Y LLAMA AL CAMBIO DE CONTEXTO
				System.out.println("--- Ciclos de reloj: " + clock + " ---");

				// espera a que todas las etapas vayan a empezar un nuevo ciclo
				barrier.await(5,TimeUnit.SECONDS);
				//printState();

			} catch (InterruptedException e) {
				break;
			} catch (BrokenBarrierException e) {
				break;
			}catch (TimeoutException e) {
			}
		}
		//printState();
		System.out.println("--- Ciclos de reloj: " + clock + " ---");
	}

	/**
	 * Metodo para verificar que las etapas sigan con vida en el
	 * pipeline
	 * @return algunaVive que indica si ya todas las etapas murieron
	 */
	private boolean algunaEtapaViva(){
		//mientras alguna viva el principal seguira ejecutandose
		boolean algunaVive = false;
		if(ifAlive || idAlive || exAlive || memAlive || wbAlive){
			algunaVive = true;
		}
		return algunaVive;
	}

	/** Calcula el bloque de memoria en el que se encuentra una direccion de memoria específica,	
	 * ej: la direccion de memoria 0768 está en el bloque 48 de memoria, pues 768 / 16 = 48 
	 * la direccion de memoria 4092 está en el bloque 256 de memoria, pues 4092/16 = 255
	 * @param dirMemoria
	 * @return
	 */
	public int calcularBloqueMemoria(int dirMemoria){
		int posBloqueMem = dirMemoria / 16;
		return posBloqueMem;
	}

	/** Calcula el bloque de cache donde se debe almacenar un bloque de memoria específico, usando 
	 * la estrategia de MAPEO DIRECTO recibe: la direccion de memoria, a la cual se le calculará 
	 * su bloque de memoria ej: la direccion 3324 está en la direccion [831] del vector de memoria 
	 * de datos, la direccion de meroria [831]  está el bloque de memoria 255 y esta en el bloque 
	 * de cache 7, pues 255 mod 8 = 1
	 * @param dirMem
	 * @return
	 */
	public int calcularBloqueCache(int dirMem){
		int bloqueMem = calcularBloqueMemoria(dirMem);
		int posBloqueCache = bloqueMem % 8;
		return posBloqueCache;
	}

	/**Metodo que verifica si la direccion de memoria es valida
	 * La memoria total es de 4096 enteros, 768 para instrucciones y 3328 para datos como cada entero 
	 * en la memoria de datos es de 4 bytes hay que hacer el calculo para accesar a la memoria de 
	 * datos así las direcciones referenciables son de 768 a 3324, siendo 768 v[0], 772 v[1], ... 3324 v[831] 
	 * debe verificarse que las direcciones de memoria sean ser multiplos de 4 y que esten entre 768 y 3324 
	 * retorna true si la direccion es valida, de lo contrario devuelve false
	 * @param dir
	 * @return
	 */
	public boolean verificarDirMem(int dir){
		boolean valida=false;
		//primero verifica si la direccion es valida
		if((dir>767 && dir<4093) && (dir % 4 == 0 )){
			valida = true;
		}
		return valida;
	}

	/** No confundir con instruccion LOAD
	 *  Guarda un bloque en cache, traído desde memoria recibe la 
	 * posicion de memoria en la cual esta el dato que se quiere cargar
	 * @param dirMemoria
	 */
	public void cacheLoad(int dirMemoria){
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(dirMemoria);
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 16 enteros
			cache[bloqueCache].setBloquePos(i, dataMem[(bloqueMem-48)*4+i]);
		}
		cache[bloqueCache].setEtiqueta(bloqueMem);
	}//fin del metodo cacheLoad

	/** No confundir con instruccion STORE 
	 * Guarda en memoria un bloque que está en la cache de datos recibe el numero de bloque de cache 
	 * que se quiere guardar (que basta para saber el bloque en memoria)
	 * @param numBloqueCache
	 */
	public void cacheStore(int numBloqueCache){
		int dirMem = cache[numBloqueCache].getEtiqueta()*4 - 192; //direccion a partir de la que hay que guardar
		for(int i=0; i<4; ++i){//4 porque cada bloque contiene 4 enteros
			dataMem[dirMem+i] = cache[numBloqueCache].getValor(i);
		}
	}//fin del metodo cacheStore

	/** Metodo para saber si un bloque de memoria está guardado en la cache recibe la direccion de 
	 * memoria que se quiere accesar devuelve true si está en cache, de lo contrario devuleve false
	 * @param dirMemoria
	 * @return
	 */
	public boolean hitMemoria(int dirMemoria){
		boolean hit = false;
		int bloqueMem = calcularBloqueMemoria(dirMemoria);
		int bloqueCache = calcularBloqueCache(dirMemoria);
		if(cache[bloqueCache].getEtiqueta() == bloqueMem){
			hit = true;
		}
		return hit;
	}//fin del metodo hitMemoria

	/**
	 * Metodo que imprime el estado de todas las variables dle MIPS
	 */
	void imprimirEstado(){
		//imprime el estado de los registros
		System.out.format("-----------------REGISTROS-----------------\n");
		int count=0;
		for(int i=0; i<8; ++i){
			for(int j=0; j<4; ++j){
				System.out.format("R%02d" + ": " + "%04d, ", count, R[count]);
				++count;
			}
			System.out.println();
		}

		//imprime la memoria de instrucciones
		System.out.println("\n----MEMORIA DE INSTRUCCIONES----");
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
		System.out.println("\n----------MEMORIA CACHÉ---------");
		count=0;
		for(int i=0; i<8; ++i){
			System.out.format("Bloque%02d: ", i);
			for(int j=0; j<4; ++j){
				System.out.format("%04d" + ", ", cache[i].getValor(j));
			}
			System.out.println("status: " + cache[i].getEstado() + ", etiqueta: " + cache[i].getEtiqueta());
		}

		//agregar imprimir middle stages, pc, IR, etc

	}//fin del metodo imprimirEstado


	//TODO: DE AQUI PARA ABAJO METODOS DE PRUEBA QUE HAY QUE BORRAR
	public void fetch(){

		if(IR[0] == FIN){
			ifAlive = false;
		}

		for(int i = 0; i < 4; ++i){
			IR[i] = instructionMem[PC+i];
		}

		// Guarda la instrucción a ejecutar en IR
		// hasta que ID este desocupado se ejecuta

		for(int i = 0; i < 4; ++i){
			IF_ID[i] = IR[i];
		}

		//*****
		System.out.println("ENTRO: IF_ID: " + IF_ID[0] +
				IF_ID[1] + IF_ID[2] + IF_ID[3]);
		// Aumenta el PC
		PC += 4;
		// Espera a que ID se desocupe con un lock o algo así		
	}//fin metodo fetch

	public void decode(){

		//System.out.println("ENtro ID");

		switch(IF_ID[0]){//contiene el código de instruccion
		case DADDI:
			ID_EX[0] = R[IF_ID[1]]; 		// RY
			ID_EX[1] = IF_ID[2];          	// X
			ID_EX[2] = IF_ID[3];          	// n
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case DADD:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Destino
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case DSUB:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Destino
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case DMUL:
			ID_EX[0] = R[IF_ID[1]]; 		// Reg Operando1
			ID_EX[1] = R[IF_ID[2]]; 		// Reg Operando2
			ID_EX[2] = IF_ID[3];          	// Reg Operando3
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case DDIV:
			ID_EX[0] = R[IF_ID[1]];			// Reg Operando1
			ID_EX[1] = R[IF_ID[2]];			// Reg Operando2
			ID_EX[2] = IF_ID[3];			// Reg Operando3
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case LW:
			ID_EX[0] = IF_ID[3]; 			// valor inmediato
			ID_EX[1] = R[IF_ID[1]];  		// Origen
			ID_EX[2] = IF_ID[2];          	// Destino
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case SW:
			ID_EX[0] = IF_ID[3]; 			// valor inmediato
			ID_EX[1] = R[IF_ID[2]]; 		// Origen
			ID_EX[2] = R[IF_ID[1]];			// Destino
			ID_EX[3] = IF_ID[0];			//operation code
			break;
		case LL:

			break;
		case SC:

			break;

		}//fin del switch

		//System.out.println("ENTRO: ID_EX: " + ID_EX[0] + ID_EX[1] + ID_EX[2] + ID_EX[3]);

	}//fin metodo decode

	public void execute(){
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
			EX_MEM[2] = ID_EX[3]; 			//codigo de operacion
			break;
		case SW:
			EX_MEM[0] = ID_EX[0]+ID_EX[2];	//Resultado de memoria del ALU
			EX_MEM[1] = ID_EX[1];			//Destino
			EX_MEM[2] = ID_EX[3]; 			//codigo de operacion
			break;
		}//fin del switch
	}//fin metodo execute

	public void memory(){
		boolean direccionValida;
		int bloqueMem, bloqueCache;
		switch(EX_MEM[2]){
		case LW:
			/*codigo viejo, sin implementacion de cache
				MEM_WB[0] = dataMem[EX_MEM[0]]; //
				MEM_WB[1] = EX_MEM[1];			//
				MEM_WB[2] = EX_MEM[2]; 			//codigo de operacion */

			//primero hay que verificar que la direccion que se tratará de leer sea valida
			direccionValida = verificarDirMem(EX_MEM[0]);
			if(direccionValida){//si diera false, sería bueno agregar un manejo de excepcion
				//si fuera válida, hay que verificar que haya un hit de memoria en cache.
				//si diera fallo, hay que traer el bloque desde memoria, si estuviera se toma directo de la cache
				bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
				bloqueCache = calcularBloqueCache(EX_MEM[0]);
				if(!hitMemoria(EX_MEM[0])){
					//si hubiera fallo de memoria y el bloque en cache correspondiente está modificado,
					//primero hay que guardarlo a memoria. para esto primero calculamos el bloque en memoria
					//del dato que se quiere cargar. es aqui cuando se usa la estrategia WRITE ALLOCATE
					if(cache[bloqueCache].getEstado() == 'm'){
						cacheStore(bloqueCache);
					}
					//en este punto se hace se carga la cache, pues no hubo hit de memoria
					//y si el bloque estaba modificado ya se guardó antes a memoria
					//es aqui cuando se usa la estrategia de WRITE BACK
					cacheLoad(EX_MEM[0]);
				}
				//en este punto sí hubo hit de memoria, por lo que se carga el dato desde la cache
				int indice = ((EX_MEM[0]+768) / 16 ) % 4;
				MEM_WB[0] = cache[bloqueCache].getValor(indice);
				//System.out.println(MEM_WB[0]);
				MEM_WB[1] = EX_MEM[1];			//
				MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion			
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
				//si fuera valida, se verifica si el bloque de memoria está en cache
				bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
				bloqueCache = calcularBloqueCache(EX_MEM[0]);
				if(!hitMemoria(EX_MEM[0])){
					//si no hay hit de memoria hay que cargar el bloque a cache, pero si en el bloque de cache
					//donde vamos a escribir está modificado, primero hay que escribir el bloque actual a memoria:
					if(cache[bloqueCache].getEstado() == 'm'){
						cacheStore(bloqueCache);
					}
					//si no estuviera modificado, entonces ya podemos escribir el bloque de cache a memoria
					cacheLoad(EX_MEM[0]);
				}
				//con el bloque ya en cache, debemos escribir en la posicion correcta del bloque
				int offset = (EX_MEM[0] / 4) % 4; //se calcula el desplazamiento en el bloque
				cache[bloqueCache].setBloquePos(offset, EX_MEM[1]); //el valor a guardar esta en EX_MEM[1]
				cache[bloqueCache].setEstado('m'); //cuando se escribe en cache el estado debe pasar a modificado

				//pasa los valores a MEM_WB
				MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
				MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
				MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion
			}
			break;

		case LL://hace exactamente lo mismo que un load normal, pero ademas de eso escribe el link register
			direccionValida = verificarDirMem(EX_MEM[0]);
			if(direccionValida){//si diera false, sería bueno agregar un manejo de excepcion
				//si fuera válida, hay que verificar que haya un hit de memoria en cache.
				//si diera fallo, hay que traer el bloque desde memoria, si estuviera se toma directo de la cache
				bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
				bloqueCache = calcularBloqueCache(EX_MEM[0]);
				if(!hitMemoria(EX_MEM[0])){
					//si hubiera fallo de memoria y el bloque en cache correspondiente está modificado,
					//primero hay que guardarlo a memoria. para esto primero calculamos el bloque en memoria
					//del dato que se quiere cargar. es aqui cuando se usa la estrategia WRITE ALLOCATE
					if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
						cacheStore(bloqueCache);
					}
					//en este punto se hace se carga la cache, pues no hubo hit de memoria
					//y si el bloque estaba modificado ya se guardó antes a memoria
					//es aqui cuando se usa la estrategia de WRITE BACK
					cacheLoad(EX_MEM[0]);
				}
				//en este punto sí hubo hit de memoria, por lo que se carga el dato desde la cache
				int indice = ((EX_MEM[0]+768) / 16 ) % 4;
				MEM_WB[0] = cache[bloqueCache].getValor(indice);
				//System.out.println(MEM_WB[0]);
				MEM_WB[1] = EX_MEM[1];			//
				MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion			
			}//fin de if direccionValida
		break;
		case SC://igual a SW, solo que se hace solo si el register link es igual a la direccion de la memoria y pone 1 en Rx
				//si no fueran iguales, entonces no pasa nada en memoria y pone un 0 en Rx
			if(true){
				direccionValida = verificarDirMem(EX_MEM[0]);
				if(direccionValida){
					//si fuera valida, se verifica si el bloque de memoria está en cache
					bloqueMem = calcularBloqueMemoria(EX_MEM[0]);
					bloqueCache = calcularBloqueCache(EX_MEM[0]);
					if(!hitMemoria(EX_MEM[0])){
						//si no hay hit de memoria hay que cargar el bloque a cache, pero si en el bloque de cache
						//donde vamos a escribir está modificado, primero hay que escribir el bloque actual a memoria:
						if(cache[bloqueCache].getEtiqueta() == bloqueMem && cache[bloqueCache].getEtiqueta() == 'm'){
							cacheStore(bloqueCache);
						}
						//si no estuviera modificado, entonces ya podemos escribir el bloque de cache a memoria
						cacheLoad(EX_MEM[0]);
					}
					//con el bloque ya en cache, debemos escribir en la posicion correcta del bloque
					int offset = (EX_MEM[0] / 4) % 4; //se calcula el desplazamiento en el bloque
					cache[bloqueCache].setBloquePos(offset, EX_MEM[1]); //el valor a guardar esta en EX_MEM[1]
					cache[bloqueCache].setEstado('m'); //cuando se escribe en cache el estado debe pasar a modificado

					//pasa los valores a MEM_WB
					MEM_WB[0] = EX_MEM[0];			//no hace nada pero tiene que pasar el valor
					MEM_WB[1] = EX_MEM[1];			//no hace nada pero tiene que pasar el valor
					MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB[2] va el codigo de operacion
				}
			}
			else{
				
			}
		break;
		default:
			MEM_WB[0] = EX_MEM[0];
			MEM_WB[1] = EX_MEM[1];
			MEM_WB[2] = EX_MEM[2]; 			//en MEM_WB va el codigo de operacion
		}//fin del switch
	}//fin metodo memory

	public void writeBack(){
		switch(MEM_WB[2]){ //codigo de operacion en MEM_WB[2]
		case DADDI:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
		case DADD:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
		case DSUB:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
		case DMUL:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
		case DDIV:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
		case LW:
			R[MEM_WB[1]] = MEM_WB[0];
			break;
			//en RW no hace nada en la etapa de writeback
		}//fin del switch
	}//fin metodo writeBack

}//fin de la clase
