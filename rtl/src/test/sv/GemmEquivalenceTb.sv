// Are the Chisel and the SpinalHDL ComplexGemm the same circuit?
//
// The assessment's cleanest number is that four structurally different datapaths all land within
// 6% of each other at about 20 000 cells per complex multiply-accumulate. All four came out of one
// generator, so that could be a fact about Chisel and firtool rather than about arithmetic.
// SpinalHDL is an independent Scala HDL with its own Verilog backend, and `brokkr.spinal.ComplexGemm`
// is the same kernel written in it.
//
// Comparing their areas is only meaningful if they compute the same function, and this establishes
// that the way it should be established: both netlists in one simulation, identical stimulus,
// compared bit for bit on every cell every cycle. Full-range random inputs rather than the golden
// vectors, because the vectors are small amplitudes and the interesting disagreements — truncation
// of negatives, overflow of the complex subtract — live at the extremes.

module GemmEquivalenceTb;
  localparam int TILE       = 4;
  localparam int CELLS      = 16;
  localparam int DATA_WIDTH = 24;
  localparam int TRIALS     = 48;
  localparam int DEPTH      = 32;

  logic clock = 0;
  logic reset = 1;
  logic clk;

  assign clk = clock;

  logic io_clear;
  logic io_inValid;

  logic [DATA_WIDTH-1:0] io_aRe_0, io_aRe_1, io_aRe_2, io_aRe_3;
  logic [DATA_WIDTH-1:0] io_aIm_0, io_aIm_1, io_aIm_2, io_aIm_3;
  logic [DATA_WIDTH-1:0] io_bRe_0, io_bRe_1, io_bRe_2, io_bRe_3;
  logic [DATA_WIDTH-1:0] io_bIm_0, io_bIm_1, io_bIm_2, io_bIm_3;

  logic signed [DATA_WIDTH-1:0] chiselRe [CELLS];
  logic signed [DATA_WIDTH-1:0] chiselIm [CELLS];
  logic signed [DATA_WIDTH-1:0] spinalRe [CELLS];
  logic signed [DATA_WIDTH-1:0] spinalIm [CELLS];

  int failures  = 0;
  int compares  = 0;
  int nonzero   = 0;

  // `.*` fills every port whose name matches a signal here, which is why the two generators
  // agreeing on `io_aRe_0` is worth something: only the outputs have to be separated by hand.
  ComplexGemm chiselDut (
    .io_outRe_0(chiselRe[0]), .io_outIm_0(chiselIm[0]),
    .io_outRe_1(chiselRe[1]), .io_outIm_1(chiselIm[1]),
    .io_outRe_2(chiselRe[2]), .io_outIm_2(chiselIm[2]),
    .io_outRe_3(chiselRe[3]), .io_outIm_3(chiselIm[3]),
    .io_outRe_4(chiselRe[4]), .io_outIm_4(chiselIm[4]),
    .io_outRe_5(chiselRe[5]), .io_outIm_5(chiselIm[5]),
    .io_outRe_6(chiselRe[6]), .io_outIm_6(chiselIm[6]),
    .io_outRe_7(chiselRe[7]), .io_outIm_7(chiselIm[7]),
    .io_outRe_8(chiselRe[8]), .io_outIm_8(chiselIm[8]),
    .io_outRe_9(chiselRe[9]), .io_outIm_9(chiselIm[9]),
    .io_outRe_10(chiselRe[10]), .io_outIm_10(chiselIm[10]),
    .io_outRe_11(chiselRe[11]), .io_outIm_11(chiselIm[11]),
    .io_outRe_12(chiselRe[12]), .io_outIm_12(chiselIm[12]),
    .io_outRe_13(chiselRe[13]), .io_outIm_13(chiselIm[13]),
    .io_outRe_14(chiselRe[14]), .io_outIm_14(chiselIm[14]),
    .io_outRe_15(chiselRe[15]), .io_outIm_15(chiselIm[15]),
    .*
  );

  SpinalComplexGemm spinalDut (
    .io_outRe_0(spinalRe[0]), .io_outIm_0(spinalIm[0]),
    .io_outRe_1(spinalRe[1]), .io_outIm_1(spinalIm[1]),
    .io_outRe_2(spinalRe[2]), .io_outIm_2(spinalIm[2]),
    .io_outRe_3(spinalRe[3]), .io_outIm_3(spinalIm[3]),
    .io_outRe_4(spinalRe[4]), .io_outIm_4(spinalIm[4]),
    .io_outRe_5(spinalRe[5]), .io_outIm_5(spinalIm[5]),
    .io_outRe_6(spinalRe[6]), .io_outIm_6(spinalIm[6]),
    .io_outRe_7(spinalRe[7]), .io_outIm_7(spinalIm[7]),
    .io_outRe_8(spinalRe[8]), .io_outIm_8(spinalIm[8]),
    .io_outRe_9(spinalRe[9]), .io_outIm_9(spinalIm[9]),
    .io_outRe_10(spinalRe[10]), .io_outIm_10(spinalIm[10]),
    .io_outRe_11(spinalRe[11]), .io_outIm_11(spinalIm[11]),
    .io_outRe_12(spinalRe[12]), .io_outIm_12(spinalIm[12]),
    .io_outRe_13(spinalRe[13]), .io_outIm_13(spinalIm[13]),
    .io_outRe_14(spinalRe[14]), .io_outIm_14(spinalIm[14]),
    .io_outRe_15(spinalRe[15]), .io_outIm_15(spinalIm[15]),
    .*
  );

  always #5 clock = ~clock;

  task automatic tick();
    @(posedge clock);
    #1;
  endtask

  task automatic randomise();
    io_aRe_0 = $urandom; io_aRe_1 = $urandom; io_aRe_2 = $urandom; io_aRe_3 = $urandom;
    io_aIm_0 = $urandom; io_aIm_1 = $urandom; io_aIm_2 = $urandom; io_aIm_3 = $urandom;
    io_bRe_0 = $urandom; io_bRe_1 = $urandom; io_bRe_2 = $urandom; io_bRe_3 = $urandom;
    io_bIm_0 = $urandom; io_bIm_1 = $urandom; io_bIm_2 = $urandom; io_bIm_3 = $urandom;
  endtask

  task automatic compare(string where);
    for (int i = 0; i < CELLS; i++) begin
      compares += 2;
      if (chiselRe[i] !== 0 || chiselIm[i] !== 0) nonzero++;

      if (chiselRe[i] !== spinalRe[i]) begin
        if (failures < 10)
          $display("FAIL %s cell %0d re: chisel %0d spinal %0d",
                   where, i, chiselRe[i], spinalRe[i]);
        failures++;
      end
      if (chiselIm[i] !== spinalIm[i]) begin
        if (failures < 10)
          $display("FAIL %s cell %0d im: chisel %0d spinal %0d",
                   where, i, chiselIm[i], spinalIm[i]);
        failures++;
      end
    end
  endtask

  initial begin
    io_clear   = 0;
    io_inValid = 0;
    randomise();

    tick();
    reset = 0;
    tick();

    for (int trial = 0; trial < TRIALS; trial++) begin
      // Clear, then compare: both should read exactly zero whatever was there before.
      io_clear   = 1;
      io_inValid = 0;
      tick();
      io_clear = 0;
      compare($sformatf("trial %0d after clear", trial));

      for (int step = 0; step < DEPTH; step++) begin
        randomise();

        // Every fourth cycle the inputs change but inValid is low, which must not accumulate.
        io_inValid = (step % 4 != 3);
        tick();
        compare($sformatf("trial %0d step %0d", trial, step));
      end

      io_inValid = 0;
    end

    $display("compared %0d values over %0d trials of depth %0d; %0d were non-zero",
             compares, TRIALS, DEPTH, nonzero);

    if (failures == 0)
      $display("PASS GemmEquivalence: Chisel and SpinalHDL agree bit for bit");
    else
      $display("FAILED with %0d mismatches", failures);

    $finish;
  end
endmodule
